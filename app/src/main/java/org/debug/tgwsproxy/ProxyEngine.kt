// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONObject

/**
 * Состояние прокси, как его видит UI (разбор `snapshot()` из Python).
 */
/**
 * Счётчики ядра (`proxy.stats`) так, как их отдаёт `snapshot()`.
 *
 * Направления названы так, как их считает ядро, — и в интерфейсе их легко
 * перепутать, поэтому дальше везде используются [inbound]/[outbound]:
 *
 *  * `up` (`stats.bytes_up`) растёт в `bridge.tcp_to_ws()`: это байты,
 *    принятые ОТ клиента (Telegram) и отправленные на сервер, то есть
 *    **исходящий** трафик телефона;
 *  * `down` (`stats.bytes_down`) растёт в `bridge.ws_to_tcp()`: байты,
 *    принятые с сервера и отданные клиенту, то есть **входящий**.
 */
data class ProxyStats(
    val total: Int = 0,
    val active: Int = 0,
    val ws: Int = 0,
    val h2: Int = 0,
    val cf: Int = 0,
    val errors: Int = 0,
    val up: String = "",
    val down: String = "",
) {
    /** Входящий трафик: сколько телефон получил (в ядре — `bytes_down`). */
    val inbound: String get() = down

    /** Исходящий трафик: сколько телефон отправил (в ядре — `bytes_up`). */
    val outbound: String get() = up
}

data class ProxyUiState(
    val state: String = STATE_STOPPED,
    val error: String = "",
    val errorKey: String = "",
    val errorArgs: List<String> = emptyList(),
    val host: String = "",
    val port: Int = 0,
    val link: String = "",
    val coreVersion: String = "",
    val uptime: Int = 0,
    val stats: ProxyStats = ProxyStats(),
) {
    /** Текст ошибки для пользователя: перевод по ключу из ядра либо запасной. */
    fun errorMessage(context: Context): String =
        context.localize(MessageKey(errorKey, errorArgs), error)

    val isActive: Boolean get() = state == STATE_STARTING || state == STATE_RUNNING ||
            state == STATE_STOPPING
    val isRunning: Boolean get() = state == STATE_RUNNING
    val address: String get() = if (port > 0) "$host:$port" else ""

    companion object {
        const val STATE_STOPPED = "stopped"
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_STOPPING = "stopping"
        const val STATE_ERROR = "error"
    }
}

/**
 * Ответ моста на `start()`/`stop()`.
 *
 * @param error текст из ядра — для logcat и как запасной вариант, если ключа
 *        перевода в сборке нет
 * @param errorKey ключ перевода (`error_key` из Python либо свой, см. [Messages.kt])
 * @param errorArgs аргументы плейсхолдеров перевода
 */
data class StartResult(
    val ok: Boolean,
    val error: String = "",
    val errorKey: String = "",
    val errorArgs: List<String> = emptyList(),
) {
    /** Сообщение для пользователя. При успехе — пустая строка. */
    fun message(context: Context): String =
        context.localize(MessageKey(errorKey, errorArgs), error)
}

/**
 * Лог ядра глазами UI — разбор `log_info()` из Python.
 *
 * @param enabled включена ли запись в файл (переключатель «Писать лог в файл»)
 * @param verbose включён ли подробный уровень DEBUG
 * @param exists создан ли `proxy_log.txt`
 * @param source откуда строки: [SOURCE_FILE] — из файла, [SOURCE_MEMORY] — из
 *        кольцевого буфера процесса (работает и когда файл выключен),
 *        [SOURCE_NONE] — логов ещё нет
 * @param lines последние строки лога (сколько попросили у [ProxyEngine.logInfo])
 */
data class ProxyLogInfo(
    val ok: Boolean = false,
    val enabled: Boolean = false,
    val verbose: Boolean = false,
    val path: String = "",
    val exists: Boolean = false,
    val size: Long = 0,
    val source: String = SOURCE_NONE,
    val error: String = "",
    val lines: List<String> = emptyList(),
) {
    /** Весь хвост лога одной строкой — для буфера обмена («Копировать лог»). */
    val text: String get() = lines.joinToString("\n")

    val hasLines: Boolean get() = lines.isNotEmpty()

    companion object {
        const val SOURCE_FILE = "file"
        const val SOURCE_MEMORY = "memory"
        const val SOURCE_NONE = "none"
    }
}

/**
 * Итог переподключения после смены сети.
 *
 * @param clients сколько клиентских сессий (Telegram -> прокси) было оборвано:
 *        они reconnect-ятся сами, локальный порт не закрывался
 * @param h2 пересоздан ли пул HTTP/2 до Cloudflare (он включён не всегда)
 */
data class ReconnectResult(
    val ok: Boolean,
    val error: String = "",
    val errorKey: String = "",
    val errorArgs: List<String> = emptyList(),
    val clients: Int = 0,
    val h2: Boolean = false,
) {
    /** Сообщение для пользователя. При успехе — пустая строка. */
    fun message(context: Context): String =
        context.localize(MessageKey(errorKey, errorArgs), error)
}

/**
 * Мост в Python-ядро (`app/src/main/python/proxy_runner.py`).
 *
 * Здесь и только здесь приложение зовёт Python. Ядро вендорится как есть и
 * обновляется копированием папки `proxy/` из апстрима Flowseal.
 */
object ProxyEngine {

    private const val TAG = "TGWS"

    // Ключи переводов (значения — имена строк в res/values/strings.xml,
    // словарь «ключ -> ресурс» живёт в Messages.kt).
    private const val KEY_PYTHON_UNAVAILABLE = "error_python_unavailable"
    private const val KEY_STOP_FAILED = "error_stop_failed"
    private const val KEY_RECONNECT = "error_reconnect"

    private var module: PyObject? = null
    private val lock = Any()

    private fun module(): PyObject = synchronized(lock) {
        module ?: Python.getInstance().getModule("proxy_runner").also { module = it }
    }

    /** Поднять прокси. Настройки — JSON-строка (ключи как в Kivy-версии). */
    fun start(settingsJson: String): StartResult = try {
        val raw = module().callAttr("start", settingsJson).toString()
        parseResult(raw).also {
            if (!it.ok) Log.w(TAG, "старт отклонён: ${it.error}")
        }
    } catch (exc: Exception) {
        Log.e(TAG, "не удалось вызвать Python-ядро", exc)
        StartResult(false, "python bridge unavailable: ${exc.message}",
            KEY_PYTHON_UNAVAILABLE, listOf(exc.message.orEmpty()))
    }

    /** Штатная остановка: сервер гасит сокеты, ядро завершает задачи. */
    fun stop(): StartResult = try {
        parseResult(module().callAttr("stop").toString())
    } catch (exc: Exception) {
        Log.e(TAG, "ошибка остановки", exc)
        StartResult(false, "stop failed: ${exc.message}",
            KEY_STOP_FAILED, listOf(exc.message.orEmpty()))
    }

    /**
     * Пересобрать исходящие соединения после смены сети (Wi-Fi <-> мобильные
     * данные, возврат связи): ядро гасит мёртвые сессии WS/CF и набирает
     * свежие, а локальный порт для Telegram НЕ закрывается — перезапускать
     * прокси целиком не нужно. Вызывать не из главного потока.
     *
     * `ok = false` означает «прокси сейчас не запущен» или «ядро не ответило»;
     * состояние прокси в обоих случаях не изменилось.
     */
    fun reconnect(reason: String): ReconnectResult = try {
        parseReconnect(module().callAttr("reconnect", reason).toString())
    } catch (exc: Exception) {
        Log.e(TAG, "не удалось переподключить ядро", exc)
        ReconnectResult(false, "reconnect failed: ${exc.message}",
            KEY_RECONNECT, listOf(exc.message.orEmpty()))
    }

    /** Текущее состояние + статистика (вызывать не из главного потока). */
    fun snapshot(): ProxyUiState = try {
        parseSnapshot(module().callAttr("snapshot").toString())
    } catch (exc: Exception) {
        Log.e(TAG, "не удалось прочитать состояние", exc)
        ProxyUiState(state = ProxyUiState.STATE_ERROR,
            error = "python bridge unavailable: ${exc.message}",
            errorKey = KEY_PYTHON_UNAVAILABLE,
            errorArgs = listOf(exc.message.orEmpty()))
    }

    /**
     * Последние строки лога + путь к файлу (вызывать не из главного потока:
     * вызов идёт в Python, а тот читает файл).
     *
     * Ошибкой не бросается: если ядро ещё не стартовало или Python недоступен,
     * возвращаем пустой [ProxyLogInfo] — диагностика просто покажет «лога нет».
     */
    fun logInfo(maxLines: Int = 200): ProxyLogInfo = try {
        parseLogInfo(module().callAttr("log_info", maxLines).toString())
    } catch (exc: Exception) {
        Log.w(TAG, "не удалось прочитать лог ядра", exc)
        ProxyLogInfo()
    }

    private fun parseResult(raw: String): StartResult {
        val json = JSONObject(raw)
        return StartResult(
            ok = json.optBoolean("ok", false),
            error = json.optString("error"),
            errorKey = json.optString("error_key"),
            errorArgs = argsOf(json),
        )
    }

    /**
     * Аргументы плейсхолдеров из ответа ядра (`error_args`).
     *
     * Их набирает Python, поэтому при разборе не бросаемся: лишний или
     * недостающий аргумент не должен ронять интерфейс — [Context.localize]
     * покажет запасной текст.
     */
    private fun argsOf(json: JSONObject): List<String> =
        json.optJSONArray("error_args")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        } ?: emptyList()

    private fun parseReconnect(raw: String): ReconnectResult {
        val json = JSONObject(raw)
        return ReconnectResult(
            ok = json.optBoolean("ok", false),
            error = json.optString("error"),
            errorKey = json.optString("error_key"),
            errorArgs = argsOf(json),
            clients = json.optInt("clients"),
            h2 = json.optBoolean("h2"),
        ).also {
            if (!it.ok) Log.w(TAG, "переподключение отклонено: ${it.error}")
        }
    }

    private fun parseLogInfo(raw: String): ProxyLogInfo {
        val json = JSONObject(raw)
        val array = json.optJSONArray("lines")
        val lines = if (array == null) {
            emptyList()
        } else {
            (0 until array.length()).map { array.optString(it) }
        }
        return ProxyLogInfo(
            ok = json.optBoolean("ok", false),
            enabled = json.optBoolean("enabled", false),
            verbose = json.optBoolean("verbose", false),
            path = json.optString("path"),
            exists = json.optBoolean("exists", false),
            size = json.optLong("size", 0),
            source = json.optString("source", ProxyLogInfo.SOURCE_NONE),
            error = json.optString("error"),
            lines = lines,
        )
    }

    private fun parseSnapshot(raw: String): ProxyUiState {
        val json = JSONObject(raw)
        val stats = json.optJSONObject("stats")
        return ProxyUiState(
            state = json.optString("state", ProxyUiState.STATE_STOPPED),
            error = json.optString("error"),
            errorKey = json.optString("error_key"),
            errorArgs = argsOf(json),
            host = json.optString("host"),
            port = json.optInt("port"),
            link = json.optString("link"),
            coreVersion = json.optString("core_version"),
            uptime = json.optInt("uptime"),
            stats = ProxyStats(
                total = stats?.optInt("connections_total") ?: 0,
                active = stats?.optInt("connections_active") ?: 0,
                ws = stats?.optInt("connections_ws") ?: 0,
                h2 = stats?.optInt("connections_h2") ?: 0,
                cf = stats?.optInt("connections_cfproxy") ?: 0,
                errors = stats?.optInt("ws_errors") ?: 0,
                up = stats?.optString("up") ?: "",
                down = stats?.optString("down") ?: "",
            ),
        )
    }
}
