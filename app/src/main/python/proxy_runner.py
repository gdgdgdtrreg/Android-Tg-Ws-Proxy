# SPDX-FileCopyrightText: 2026 gdgdgdtrreg
# SPDX-License-Identifier: GPL-3.0-or-later
"""Мост между Kotlin-сервисом и вендоренным ядром `proxy/` (Chaquopy).

Kotlin-сторона вызывает пять функций:

    start(settings_json) -> str(JSON)   # поднять прокси в фоновом потоке
    stop() -> str(JSON)                 # попросить остановиться
    snapshot() -> str(JSON)             # состояние, адрес, ссылка, статистика
    reconnect(reason) -> str(JSON)      # пересобрать соединения после смены сети
    log_info(max_lines) -> str(JSON)    # путь к логу, размер и последние строки

Почему так, а не как в Kivy-версии: сервис и Python живут в ОДНОМ процессе
(Chaquopy встраивает интерпретатор в приложение), поэтому не нужны ни файлы
команд (`proxy_command.json`), ни «намерения» СТАРТ/СТОП, ни watchdog-и —
состояние читается напрямую, а остановка выполняется через asyncio.Event.

Ядро не модифицируется: настройки кладутся в `proxy.config.proxy_config`,
запуск — `proxy.tg_ws_proxy._run(stop_event)`.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
import threading
import time
import traceback
from collections import deque
from logging.handlers import RotatingFileHandler

# --------------------------------------------------------------------------
# Логи: logcat + кольцо в памяти + файл (куда скажет Kotlin)
#
# Три приёмника одновременно, потому что у каждого своя задача:
#   * logcat — для разработчика с adb (тег PROXY_DEBUG);
#   * кольцо в памяти — последние строки видны в самом приложении
#     («Настройки → Диагностика») и попадают в отчёт, даже когда запись в файл
#     выключена;
#   * файл proxy_log.txt — для долгой работы; крутится (1 МБ + копия), чтобы не
#     съесть память телефона.
#
# Папку для файла задаёт Kotlin (ProxyLog.dir → внешняя приватная папка
# приложения, /storage/emulated/0/Android/data/<пакет>/files): разрешение на
# хранилище для неё не нужно, а забрать файл можно без root — кнопкой
# «Отправить отчёт» или `adb pull`. Прежнее поведение (os.path.expanduser('~'))
# оставляло лог в /data/user/0/<пакет>/files — Chaquopy ставит HOME именно там,
# и без root оттуда ничего не достать.
# --------------------------------------------------------------------------
LOG_TAG = 'PROXY_DEBUG'
LOG_FILE_NAME = 'proxy_log.txt'
#: Сколько байт держим в файле до прокрутки (и одна копия рядом).
LOG_MAX_BYTES = 1_000_000
LOG_BACKUP_COUNT = 1
#: Сколько строк помним в процессе, когда файл выключен.
LOG_RING_LINES = 1000
#: Сколько байт читаем с конца файла ради «последних строк».
LOG_TAIL_BUDGET = 256 * 1024


def _load_android_log():
    """`android.util.Log` или None вне Android.

    Chaquopy НЕ поддерживает pyjnius (Kivy-версия импортировала `jnius`, и здесь
    тот импорт молча падал — лог уходил только в stderr). Родной способ Chaquopy
    — модуль `java`; jnius оставлен запасным путём для python-for-android.
    """
    try:                                        # pragma: no cover - только Android
        from java import jclass
        return jclass('android.util.Log')
    except Exception:
        pass
    try:                                        # pragma: no cover - только p4a
        from jnius import autoclass
        return autoclass('android.util.Log')
    except Exception:
        return None


_Log = _load_android_log()


class _LogcatHandler(logging.Handler):
    """Строки в logcat с настоящим уровнем (E/W/I/D), а не одним Log.i."""

    def emit(self, record):                     # pragma: no cover - только Android
        try:
            text = self.format(record)
            if record.levelno >= logging.ERROR:
                _Log.e(LOG_TAG, text)
            elif record.levelno >= logging.WARNING:
                _Log.w(LOG_TAG, text)
            elif record.levelno >= logging.DEBUG:
                _Log.d(LOG_TAG, text)
            else:
                _Log.i(LOG_TAG, text)
        except Exception:
            pass


_ring: deque = deque(maxlen=LOG_RING_LINES)
_ring_lock = threading.Lock()


class _RingHandler(logging.Handler):
    """Кольцевой буфер последних строк — лог приложения без файла."""

    def emit(self, record):
        try:
            with _ring_lock:
                _ring.append(self.format(record))
        except Exception:
            pass


_log_path = ''
_log_error = ''
_log_enabled = False
_log_verbose = False


def _default_log_dir() -> str:
    """Папка для файла, если Kotlin её не передал (десктоп, тесты)."""
    return os.environ.get('ANDROID_PRIVATE') or os.path.expanduser('~')


def _setup_logging(verbose: bool, log_to_file: bool, log_dir: str = '') -> None:
    """Пересобрать корневой логгер: уровень, logcat, кольцо, файл."""
    global _log_path, _log_error, _log_enabled, _log_verbose

    root = logging.getLogger()
    for handler in list(root.handlers):
        try:
            handler.close()
        except Exception:
            pass
        root.removeHandler(handler)
    root.setLevel(logging.DEBUG if verbose else logging.INFO)
    _log_verbose = bool(verbose)
    _log_enabled = bool(log_to_file)
    _log_path = ''
    _log_error = ''

    stamped = '%(asctime)s %(levelname)-7s %(name)s: %(message)s'

    if _Log is not None:                        # Android: logcat
        logcat = _LogcatHandler()
        logcat.setFormatter(logging.Formatter('%(name)s: %(message)s'))
        root.addHandler(logcat)
    else:                                       # десктоп/тесты: stderr
        stream = logging.StreamHandler()
        stream.setFormatter(logging.Formatter('%(levelname)s %(name)s: %(message)s'))
        root.addHandler(stream)

    ring = _RingHandler()
    ring.setFormatter(logging.Formatter(stamped, datefmt='%H:%M:%S'))
    root.addHandler(ring)

    if not log_to_file:
        return

    folder = (log_dir or '').strip() or _default_log_dir()
    path = os.path.join(folder, LOG_FILE_NAME)
    _log_path = path
    try:
        os.makedirs(folder, exist_ok=True)
        file_handler = RotatingFileHandler(
            path, maxBytes=LOG_MAX_BYTES, backupCount=LOG_BACKUP_COUNT,
            encoding='utf-8')
        file_handler.setFormatter(logging.Formatter(stamped))
        root.addHandler(file_handler)
    except OSError as exc:
        # Файл не завёлся (нет папки, кончилось место) — не повод ронять прокси:
        # logcat и кольцо в памяти продолжают работать, а причина видна в
        # диагностике через log_info()['error'].
        _log_error = f'{exc}'


def _tail_lines(path: str, count: int, budget: int = LOG_TAIL_BUDGET) -> list:
    """Последние `count` строк файла, читая с конца не больше `budget` байт."""
    if not path or count <= 0:
        return []
    try:
        size = os.path.getsize(path)
        with open(path, 'rb') as handle:
            if size > budget:
                handle.seek(size - budget)
                handle.readline()               # отбрасываем обрывок строки
            data = handle.read()
    except OSError:
        return []
    return data.decode('utf-8', 'replace').splitlines()[-count:]


def _ring_lines(count: int) -> list:
    with _ring_lock:
        return list(_ring)[-count:]


def log_info(max_lines: int = 200) -> str:
    """Лог для экрана «Диагностика»: путь, размер и последние строки.

    Возвращает JSON: {"ok", "enabled", "verbose", "path", "exists", "size",
    "source", "error", "lines"}. `source`: "file" — строки из proxy_log.txt,
    "memory" — из кольца процесса (файла ещё нет или он пуст), "none" — логов ещё
    нет. Kotlin показывает эти строки в «Диагностике» и кладёт в буфер обмена по
    кнопке «Копировать лог» — так лог достаётся без root и без adb.
    """
    try:
        count = max(1, min(int(max_lines), 2000))
    except (TypeError, ValueError):
        count = 200

    lines = _tail_lines(_log_path, count)
    source = 'file' if lines else 'none'
    if lines and len(lines) < count:
        # файл только что провернулся — добираем строки из копии
        backup = _tail_lines(f'{_log_path}.1', count - len(lines))
        if backup:
            lines = backup + lines
    if not lines:
        lines = _ring_lines(count)
        source = 'memory' if lines else 'none'

    exists = False
    size = 0
    if _log_path:
        try:
            size = os.path.getsize(_log_path)
            exists = True
        except OSError:
            exists = False

    return json.dumps({
        'ok': True,
        'enabled': _log_enabled,
        'verbose': _log_verbose,
        'path': _log_path or os.path.join(_default_log_dir(), LOG_FILE_NAME),
        'exists': exists,
        'size': size,
        'source': source,
        'error': _log_error,
        'lines': lines,
    })


# --------------------------------------------------------------------------
# Настройки: те же ключи, что были у Kivy-версии (чтобы перенос был 1:1)
# --------------------------------------------------------------------------
DEFAULT_SETTINGS = {
    'ip': '127.0.0.1',
    'allow_lan': False,
    'port': 1443,
    'secret': '',
    'dcs': '2:149.154.167.220\n4:149.154.167.220',
    'force_test_dc': False,
    'cf_proxy_enable': True,
    'cf_proxy_domain': '',
    'cf_worker_domain': '',
    'h2_media': True,
    'no_secure': False,
    'fake_tls_domain': '',
    'buffer': 256,
    'pool': 4,
    'verbose': False,
    'log_to_file': False,
    # папка для proxy_log.txt — передаёт Kotlin (ProxyLog.dir); пустая строка
    # значит «возьми запасную» (_default_log_dir)
    'log_dir': '',
}


#: Ключи сообщений для интерфейса. Сам перевод живёт в
#: `app/src/main/res/values/strings.xml` (и `values-en/`), а словарь
#: «ключ -> ресурс» — в `Messages.kt`. Python отдаёт только ключ и аргументы
#: плейсхолдеров: ядро не знает про ресурсы Android, зато сообщение можно
#: перевести, не трогая Python.
KEY_SETTINGS_JSON = 'error_settings_json'
KEY_SETTINGS_NOT_OBJECT = 'error_settings_not_object'
KEY_SECRET_HEX = 'error_secret_hex'
KEY_DC_ENTRY = 'error_dc_entry'
KEY_DC_LIST = 'error_dc_list'
KEY_CORE_START = 'error_core_start'
KEY_PORT_DEAD = 'error_port_dead'
KEY_CORE_CRASH = 'error_core_crash'
KEY_STOP_TIMEOUT = 'error_stop_timeout'
KEY_STOPPED_NOTE = 'error_stopped_note'
KEY_NOT_RUNNING = 'error_not_running'
KEY_RECONNECT = 'error_reconnect'


class SettingsError(ValueError):
    """Некорректные настройки — сервис сообщит об этом и не стартует.

    @param message русский текст — для logcat и как запасной вариант в
        интерфейсе, если ключа в сборке нет
    @param key ключ перевода (см. `Messages.kt`)
    @param details аргументы плейсхолдеров перевода (`%1$s` и т.п.)
    """

    def __init__(self, message: str, key: str = '', details: list | None = None):
        super().__init__(message)
        self.key = key
        self.details = [str(item) for item in (details or [])]


def _failure(message: str, key: str = '', details: list | None = None,
             **extra) -> str:
    """JSON отказа: `error` (текст для лога) + `error_key`/`error_args`."""
    payload = {'ok': False, 'error': message, 'error_key': key,
               'error_args': [str(item) for item in (details or [])]}
    payload.update(extra)
    return json.dumps(payload)


def _as_int(value, default: int, lo: int, hi: int) -> int:
    try:
        number = int(str(value).strip())
    except (TypeError, ValueError):
        return default
    if number < lo or number > hi:
        return default
    return number


def _as_bool(value) -> bool:
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in ('1', 'true', 'yes', 'on')


def load_settings(settings_json: str) -> dict:
    """JSON из Kotlin -> провалидированный dict для ядра."""
    try:
        parsed = json.loads(settings_json) if settings_json else {}
    except ValueError as exc:
        raise SettingsError(f'Не удалось разобрать настройки: {exc}',
                            KEY_SETTINGS_JSON, [exc]) from None
    if not isinstance(parsed, dict):
        raise SettingsError('Настройки должны быть объектом JSON',
                            KEY_SETTINGS_NOT_OBJECT)

    settings = dict(DEFAULT_SETTINGS)
    settings.update(parsed)

    secret = str(settings.get('secret') or '').strip().lower()
    if secret and (len(secret) != 32
                   or any(c not in '0123456789abcdef' for c in secret)):
        raise SettingsError('Секрет должен быть 32 hex-символа (0-9, a-f)',
                            KEY_SECRET_HEX)
    if not secret:
        secret = os.urandom(16).hex()

    settings['allow_lan'] = _as_bool(settings.get('allow_lan'))
    host = '0.0.0.0' if settings['allow_lan'] else '127.0.0.1'
    if not settings['allow_lan'] and settings.get('ip') not in (None, '',
                                                                '127.0.0.1'):
        host = str(settings['ip']).strip() or '127.0.0.1'

    dc_lines = [line.strip() for line in
                str(settings.get('dcs') or '').splitlines()]
    dc_lines = [line for line in dc_lines if line and not line.startswith('#')]
    if not dc_lines:
        dc_lines = DEFAULT_SETTINGS['dcs'].splitlines()

    settings.update(
        host=host,
        port=_as_int(settings.get('port'), 1443, 1, 65535),
        secret=secret,
        dcs='\n'.join(dc_lines),
        buffer=_as_int(settings.get('buffer'), 256, 16, 8192),
        pool=_as_int(settings.get('pool'), 4, 0, 64),
        fake_tls_domain=str(settings.get('fake_tls_domain') or '').strip(),
    )
    return settings


def apply_settings(settings: dict) -> dict:
    """Переносим настройки в `proxy.config.proxy_config` (ядро не меняем).

    Поля новых версий ядра ставятся через hasattr — как в Kivy-версии.
    """
    from proxy import (coerce_domain_list, config as proxy_config_module,
                       parse_dc_ip_list)

    cfg = proxy_config_module.proxy_config

    try:
        # ядро ждёт СПИСОК строк ('2:149.154.167.220'), а не одну строку
        dc_redirects = parse_dc_ip_list(
            [line for line in settings['dcs'].splitlines() if line.strip()])
    except ValueError as exc:
        bad = getattr(exc, 'entry', None)
        if bad is not None:
            raise SettingsError(f'Датацентры: строка «{bad}» не похожа на '
                                f'DC:IP (например 2:149.154.167.220)',
                                KEY_DC_ENTRY, [bad]) from None
        raise SettingsError(f'Датацентры: {exc}', KEY_DC_LIST, [exc]) from None

    cfg.host = settings['host']
    cfg.port = settings['port']
    cfg.secret = settings['secret']
    cfg.buffer_size = settings['buffer'] * 1024
    cfg.pool_size = settings['pool']
    cfg.dc_redirects = dc_redirects
    cfg.fallback_cfproxy = _as_bool(settings['cf_proxy_enable'])
    cfg.cfproxy_user_domains = coerce_domain_list(settings['cf_proxy_domain'])
    cfg.cfproxy_worker_domains = coerce_domain_list(settings['cf_worker_domain'])

    applied = {}
    for field, value in (
        ('cfproxy_h2_media', _as_bool(settings['h2_media'])),
        ('disable_secure', _as_bool(settings['no_secure'])),
        ('fake_tls_domain', settings['fake_tls_domain']),
        ('force_test_dc', _as_bool(settings['force_test_dc'])),
    ):
        if hasattr(cfg, field):
            setattr(cfg, field, value)
            applied[field] = value
    if hasattr(cfg, 'proxy_protocol'):
        cfg.proxy_protocol = False      # на телефоне nginx/haproxy нет
    return applied


def build_link(settings: dict) -> str:
    """Ссылка tg://proxy (dd, либо ee при fake TLS) — как в Kivy-версии."""
    host = settings['host']
    if host == '0.0.0.0':
        host = _local_ip() or '127.0.0.1'
    secret = settings['secret']
    if settings['fake_tls_domain']:
        suffix = settings['fake_tls_domain'].encode('ascii', 'ignore').hex()
        return (f"tg://proxy?server={host}&port={settings['port']}"
                f"&secret=ee{secret}{suffix}")
    return f"tg://proxy?server={host}&port={settings['port']}&secret=dd{secret}"


def _local_ip() -> str | None:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(('8.8.8.8', 80))
            return sock.getsockname()[0]
    except OSError:
        return None


# --------------------------------------------------------------------------
# Состояние (читается Kotlin-стороной через snapshot())
# --------------------------------------------------------------------------
_lock = threading.Lock()
_thread: threading.Thread | None = None
_loop: asyncio.AbstractEventLoop | None = None
_stop_event: asyncio.Event | None = None
_state = 'stopped'          # stopped|starting|running|stopping|error
_error = ''
_error_key = ''             # ключ перевода ошибки (см. KEY_* и Messages.kt)
_error_args: list = []      # аргументы плейсхолдеров перевода
_settings: dict = {}
_started_at = 0.0
_core_version = ''


def _set(state: str | None = None, error: str | None = None, key: str = '',
         details: list | None = None) -> None:
    """Сменить состояние и/или ошибку.

    Ключ и аргументы перевода живут рядом с текстом ошибки и сбрасываются
    вместе с ней: `snapshot()` отдаёт их в UI, а там `Messages.kt` подставляет
    строку из `res/values*/strings.xml`.
    """
    global _state, _error, _error_key, _error_args
    with _lock:
        if state is not None:
            _state = state
        if error is not None:
            _error = error
            _error_key = key if error else ''
            _error_args = ([str(item) for item in details]
                           if error and details else [])


def _stats() -> dict:
    try:
        from proxy.stats import stats
    except Exception:
        return {}
    summary = {}
    for field in ('connections_total', 'connections_active', 'connections_ws',
                  'connections_h2', 'connections_cfproxy',
                  'connections_tcp_fallback', 'connections_bad', 'ws_errors',
                  'bytes_up', 'bytes_down'):
        summary[field] = int(getattr(stats, field, 0) or 0)
    # Направления — как их считает ядро: `bytes_up` растёт, когда прокси принял
    # байты ОТ клиента (Telegram) и отправил на сервер (для телефона это
    # ИСХОДЯЩИЙ трафик), `bytes_down` — когда принял с сервера и отдал клиенту
    # (ВХОДЯЩИЙ). Ключи JSON оставляем «up»/«down» (их же читает Kivy-версия),
    # а Kotlin раскладывает их по ProxyStats.inbound/outbound, чтобы подписи
    # «in»/«out» в интерфейсе не перепутались.
    summary['up'] = _human_bytes(summary.get('bytes_up', 0))
    summary['down'] = _human_bytes(summary.get('bytes_down', 0))
    try:
        summary['summary'] = stats.summary()
    except Exception:
        summary['summary'] = ''
    return summary


def _human_bytes(n: float) -> str:
    for unit in ('B', 'KB', 'MB', 'GB'):
        if abs(n) < 1024:
            return f'{n:.1f}{unit}'
        n /= 1024
    return f'{n:.1f}TB'


def snapshot() -> str:
    """Текущее состояние для UI: JSON-строка (Kotlin парсит org.json)."""
    with _lock:
        state, error = _state, _error
        error_key, error_args = _error_key, list(_error_args)
        settings, started = dict(_settings), _started_at
    uptime = int(time.monotonic() - started) if started else 0
    return json.dumps({
        'state': state,
        'error': error,
        'error_key': error_key,
        'error_args': error_args,
        'host': settings.get('host', ''),
        'port': settings.get('port', 0),
        'secret': settings.get('secret', ''),
        'link': build_link(settings) if settings else '',
        'core_version': _core_version,
        'uptime': uptime,
        'stats': _stats() if state in ('running', 'stopping') else {},
    })


# --------------------------------------------------------------------------
# Запуск/остановка
# --------------------------------------------------------------------------
async def _watch_listening(host: str, port: int,
                           timeout: float = 12.0) -> bool:
    """Ждём, пока порт начнёт принимать: тогда состояние = running.

    Проверяем именно соединением, а не «сервер создан»: так состояние в UI
    означает то, что видит Telegram.
    """
    address = '127.0.0.1' if host == '0.0.0.0' else host
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with _lock:
            if _state == 'stopping':
                return False
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(address, port), timeout=0.5)
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass
            return True
        except (OSError, asyncio.TimeoutError):
            await asyncio.sleep(0.2)
    return False


def _thread_main(settings: dict) -> None:
    global _loop, _stop_event, _started_at
    from proxy.tg_ws_proxy import _run

    async def _serve() -> None:
        global _loop, _stop_event, _started_at
        _loop = asyncio.get_running_loop()
        _stop_event = asyncio.Event()
        _started_at = time.monotonic()
        watcher = asyncio.create_task(
            _watch_listening(settings['host'], settings['port']))
        # состояние = running, когда порт уже принимает соединения
        asyncio.create_task(_mark_running(watcher))
        await _run(_stop_event)
        watcher.cancel()

    try:
        asyncio.run(_serve())
        _set('stopped', '')
    except Exception as exc:                # ядро упало
        logging.getLogger('proxy_runner').error('ядро упало: %r\n%s', exc,
                                                traceback.format_exc())
        _set('error', f'Прокси остановился с ошибкой: {exc}',
             KEY_CORE_CRASH, [exc])


async def _mark_running(watcher: 'asyncio.Task[bool]') -> None:
    if await watcher:
        _set('running')
    else:
        with _lock:
            stopping = _state == 'stopping'
        if not stopping:
            _set('error', 'Прокси не поднялся: порт не отвечает. '
                          'Подробности — «Настройки → Диагностика» '
                          '(последние строки лога и кнопка «Отправить отчёт»).',
                 KEY_PORT_DEAD)


def start(settings_json: str) -> str:
    """Поднять прокси. Возвращает JSON: {"ok": bool, "error": str}."""
    global _thread, _core_version

    with _lock:
        if _state in ('starting', 'running', 'stopping'):
            return json.dumps({'ok': True, 'error': ''})

    try:
        settings = load_settings(settings_json)
    except SettingsError as exc:
        _set('error', str(exc), exc.key, exc.details)
        return _failure(str(exc), exc.key, exc.details)

    try:
        from proxy import __version__ as core_version
        _core_version = core_version
        apply_settings(settings)
    except SettingsError as exc:
        _set('error', str(exc), exc.key, exc.details)
        return _failure(str(exc), exc.key, exc.details)
    except Exception as exc:
        message = f'Ошибка запуска: {exc}'
        _set('error', message, KEY_CORE_START, [exc])
        return _failure(message, KEY_CORE_START, [exc])

    _setup_logging(_as_bool(settings['verbose']),
                   _as_bool(settings['log_to_file']),
                   str(settings.get('log_dir') or ''))

    global _settings
    with _lock:
        _settings = settings
    _set('starting', '')

    _thread = threading.Thread(target=_thread_main, args=(settings,),
                               name='proxy-core', daemon=True)
    _thread.start()
    return json.dumps({'ok': True, 'error': ''})


def stop() -> str:
    """Остановить: asyncio.Event -> ядро штатно гасит сокеты и задачи."""
    global _thread
    with _lock:
        loop, event = _loop, _stop_event

    if loop is None or event is None or _thread is None:
        _set('stopped', '')
        return json.dumps({'ok': True, 'error': ''})

    _set('stopping')
    try:
        loop.call_soon_threadsafe(event.set)
    except RuntimeError as exc:              # цикл уже закрылся
        # Останавливать уже нечего: цикл событий закрылся сам. Состояние —
        # stopped (для UI это успех), а текст с ключом остаётся в диагностике.
        _set('stopped', f'Остановлено: {exc}', KEY_STOPPED_NOTE, [exc])
        return json.dumps({'ok': True, 'error': ''})

    thread = _thread
    thread.join(timeout=8.0)
    if thread.is_alive():
        message = 'Прокси не остановился за 8 секунд'
        _set('error', message, KEY_STOP_TIMEOUT)
        return _failure(message, KEY_STOP_TIMEOUT)

    _thread = None
    _set('stopped', '')
    return json.dumps({'ok': True, 'error': ''})
# --------------------------------------------------------------------------
# Переподключение при смене сети (Kotlin зовёт из NetworkCallback)
# --------------------------------------------------------------------------
#: Сколько ждём ответа ядра на переподключение (Kotlin зовёт из потока IO).
RECONNECT_TIMEOUT = 15.0


async def _reconnect_core(reason: str) -> dict:
    """Пересобрать исходящие соединения ядра, НЕ трогая локальный сервер.

    Последовательность — та же, что ядро само выполняет на старте в
    `proxy.tg_ws_proxy._run()`: сброс backoff'а TCP-фолбэка, сброс пулов WS и
    CF-worker, новый пул HTTP/2, обрыв зависших клиентских задач и повторный
    прогрев пулов. Код ядра при этом не меняется — берём его же функции и
    модульные объекты.

    Локальный порт продолжает слушать: Telegram переподключится к нему сам,
    уже через свежие сессии.
    """
    log = logging.getLogger('proxy_runner')
    from proxy import tg_ws_proxy
    from proxy.balancer import balancer
    from proxy.bridge import reset_tcp_backoff
    from proxy.cf_h2 import CfH2Pool
    from proxy.config import (proxy_config, start_cfproxy_domain_refresh)
    from proxy.pool import cf_worker_pool, ws_pool

    # 1. TCP-фолбэк: забыть, какие адреса «плохие» — при смене сети это уже
    #    не так (заодно снимает backoff до 3600 с, который иначе держал бы
    #    фолбэк выключенным ещё час).
    reset_tcp_backoff()

    # 2. Пулы исходящих WS-сессий: мёртвые сокеты закрыть, набрать свежие.
    idle_ws = sum(len(deque) for deque in getattr(ws_pool, '_idle', {}).values())
    idle_cf = sum(len(deque)
                  for deque in getattr(cf_worker_pool, '_idle', {}).values())
    ws_pool.reset()
    cf_worker_pool.reset()

    # 3. Пул HTTP/2 до Cloudflare держит живые соединения в своих «полосах»,
    #    поэтому не сбрасывается, а пересоздаётся — как на старте ядра.
    old_pool = getattr(tg_ws_proxy, 'cf_h2_pool', None)
    if old_pool is not None:
        try:
            await old_pool.close()
        except Exception as exc:
            log.debug('не удалось закрыть прежний пул CF H2: %r', exc)
    h2_enabled = bool(getattr(proxy_config, 'h2_enabled', False))
    tg_ws_proxy.cf_h2_pool = CfH2Pool() if h2_enabled else None

    # 4. Клиентские задачи (Telegram <-> прокси) обрываем сразу: их исходящие
    #    сессии мертвы, а запись в TCP поверх пропавшей сети может висеть
    #    минутами. Задачи сами уберут себя из множества по done_callback.
    client_tasks = list(getattr(tg_ws_proxy, '_client_tasks', ()) or ())
    for task in client_tasks:
        task.cancel()
    if client_tasks:
        await asyncio.gather(*client_tasks, return_exceptions=True)

    # 5. Список доменов CF-прокси перечитываем так же, как на старте: на новой
    #    сети DNS может отвечать иначе (или исчезает captive-портал).
    if proxy_config.cfproxy_user_domains:
        balancer.update_domains_list(proxy_config.cfproxy_user_domains)
    else:
        start_cfproxy_domain_refresh()

    # 6. Прогрев: свежие сессии набираются заранее, а не по первому запросу.
    await ws_pool.warmup()
    await cf_worker_pool.warmup()

    log.info('переподключение (%s): сброшено сессий WS %d, CF-worker %d, '
             'оборвано клиентов %d, пул H2 пересоздан: %s',
             reason or 'причина не указана', idle_ws, idle_cf,
             len(client_tasks), h2_enabled)
    return {'ws': idle_ws, 'cf': idle_cf,
            'clients': len(client_tasks), 'h2': h2_enabled}


def reconnect(reason: str = '') -> str:
    """Переподключиться после смены сети, не закрывая локальный порт.

    Возвращает JSON: {"ok": bool, "error": str, "clients": int, "h2": bool}.
    `ok=false` означает «прокси сейчас не запущен» либо «ядро не ответило» —
    в обоих случаях состояние прокси не изменилось.
    """
    log = logging.getLogger('proxy_runner')
    with _lock:
        loop, state = _loop, _state

    if loop is None or loop.is_closed() or state not in ('starting', 'running'):
        return _failure('Прокси не запущен — переподключать нечего',
                        KEY_NOT_RUNNING, clients=0, h2=False)

    try:
        future = asyncio.run_coroutine_threadsafe(_reconnect_core(reason), loop)
        report = future.result(timeout=RECONNECT_TIMEOUT)
    except Exception as exc:
        log.error('переподключение не выполнено: %r\n%s', exc,
                  traceback.format_exc())
        return _failure(f'Переподключение не выполнено: {exc}', KEY_RECONNECT,
                        [exc], clients=0, h2=False)

    result = {'ok': True, 'error': ''}
    result.update(report)
    return json.dumps(result)
