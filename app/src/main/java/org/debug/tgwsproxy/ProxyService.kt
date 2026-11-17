// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Foreground-сервис: держит процесс (а вместе с ним Python-ядро и сокеты) живым
 * с уведомлением и wakelock-ом.
 *
 * Устроено проще, чем Kivy/p4a-версия: сервис и UI живут в ОДНОМ процессе,
 * Python доступен через [ProxyEngine], состояние читается напрямую. Поэтому нет
 * ни файлов команд, ни «намерений» СТАРТ/СТОП, ни повторов `startService`.
 *
 * Смену сети (Wi-Fi <-> мобильные данные, пропажа и возврат связи) ловит
 * [NetworkMonitor]: исходящие сессии ядра пересобираются на месте, локальный
 * порт для Telegram не закрывается — полный перезапуск прокси не нужен.
 */
class ProxyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollJob: Job? = null
    private var networkMonitor: NetworkMonitor? = null

    /** Сервис показан системе как foreground (контракт «startForeground за 5 с»). */
    @Volatile
    private var inForeground = false

    /** Система запретила foreground-сервис: работать нельзя, гасимся сами. */
    @Volatile
    private var foregroundDenied = false

    /** Остановка уже идёт — повторные запросы её не дублируют. */
    @Volatile
    private var shuttingDown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // FGS обязан показать уведомление в первые ~5 секунд
        if (!startForegroundCompat(
                notification(getString(R.string.notif_starting), ""))) {
            // Android 12+: система не разрешила foreground-сервис (старт из фона
            // или исчерпан суточный лимит dataSync). Ждать нельзя — через
            // несколько секунд процесс убьют с RemoteServiceException, то есть
            // приложение «вылетит». Гасимся сами и сообщаем об этом вызывающей
            // стороне через Companion-функции (те получают отказ синхронно).
            foregroundDenied = true
            notifySystemStopped()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundDenied) {
            Log.w(TAG, "foreground-сервис запрещён системой — ${intent?.action} игнорируется")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                // Остановку присылают и через startForegroundService() (так
                // делает плитка, когда обычный startService() из фона запрещён).
                // Контракт «startForeground за 5 секунд» действует для любого
                // такого старта — иначе система убьёт процесс с
                // RemoteServiceException, поэтому при необходимости показываем
                // уведомление и здесь.
                if (!inForeground) {
                    startForegroundCompat(
                        notification(getString(R.string.notif_stopping), ""))
                }
                scope.launch { shutdown() }
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                pollJob?.cancel()
                scope.launch {
                    withContext(Dispatchers.IO) { ProxyEngine.stop() }
                    startProxy()
                }
            }
            else -> startProxy()
        }
        return START_STICKY
    }

    /**
     * Android 14+: система больше не разрешает этому сервису быть foreground.
     * Приходит сюда, когда исчерпан лимит времени foreground-сервиса: у
     * `dataSync` на Android 15+ это ~6 часов в сутки. С переходом на `specialUse`
     * (Android 14+) штатно не срабатывает, но обработчик остаётся страховкой —
     * например, для Android 10…13, где сервис всё ещё поднимается как `dataSync`,
     * или если прошивка вводит собственные ограничения.
     *
     * Требование системы — остановиться немедленно: если этого не сделать,
     * процесс будет убит с `ForegroundServiceDidNotStopInTimeException`, то
     * есть приложение «вылетит» само, после долгой работы и без действий
     * пользователя. Поэтому сначала [stopSelf], а ядро гасим уже фоном.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "система закрыла foreground-сервис: startId=$startId, fgsType=$fgsType")
        notifySystemStopped()
        stopSelf(startId)
        // Намерение пользователя («прокси должен работать») не меняем: лимит
        // сбросится сам, и после перезагрузки устройства прокси снова поднимется.
        scope.launch { shutdown(keepDesired = true) }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        stopNetworkMonitor()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // -- запуск/остановка ----------------------------------------------------

    private fun startProxy() {
        val store = SettingsStore(this)
        val settings = store.settingsWithSecret()
        val problem = ProxySettings.validate(settings)
        if (problem != null) {
            val error = localize(problem)
            Log.w(TAG, "настройки не прошли проверку: $error")
            updateNotification(getString(R.string.notif_settings_error), error)
            scope.launch { shutdown() }
            return
        }

        ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?.cancel(SYSTEM_STOP_NOTIFICATION_ID)
        acquireWakeLock()
        updateNotification(getString(R.string.notif_starting),
            settings.let { "${it.ip}:${it.port}" })
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ProxyEngine.start(settingsJson(settings))
            }
            if (!result.ok) {
                updateNotification(getString(R.string.notif_error),
                    result.message(this@ProxyService))
                withContext(Dispatchers.IO) { ProxyEngine.stop() }
                // сервис гасим: держать уведомление «Ошибка» незачем
                shutdown()
                return@launch
            }
            store.desiredRunning = true
            startPolling()
            startNetworkMonitor()
        }
    }

    /**
     * JSON настроек для ядра плюс папка для лога.
     *
     * Python пишет `proxy_log.txt` туда, куда мы скажем, и говорим мы — внешняя
     * приватная папка приложения ([ProxyLog.dir]): разрешение на хранилище не
     * нужно, а забрать файл можно без root (см. [ProxyLog]). Без этого ключа
     * Python взял бы `HOME`, а Chaquopy ставит его во внутреннюю область
     * `/data/user/0/<пакет>/files` — оттуда лог без root не достать.
     */
    private fun settingsJson(settings: ProxySettings): String = try {
        JSONObject(settings.toJson())
            .put("log_dir", ProxyLog.dir(this).absolutePath)
            .toString()
    } catch (exc: Exception) {
        Log.e(TAG, "не удалось добавить папку лога в настройки", exc)
        settings.toJson()
    }

    /**
     * @param keepDesired не трогать «пользователь хочет, чтобы прокси работал» —
     *        так останавливает сервис система (лимит dataSync), а не пользователь.
     */
    private suspend fun shutdown(keepDesired: Boolean = false) {
        if (shuttingDown) return
        shuttingDown = true
        pollJob?.cancel()
        stopNetworkMonitor()
        withContext(Dispatchers.IO) { ProxyEngine.stop() }
        if (!keepDesired) {
            SettingsStore(this@ProxyService).desiredRunning = false
        }
        inForeground = false
        releaseWakeLock()
        ServiceCompat.stopForeground(this@ProxyService,
            ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -- смена сети ----------------------------------------------------------

    /**
     * Начать следить за сетью. Повторный вызов (рестарт ядра по «Сохранить»)
     * сначала снимает прежний приёмник, чтобы события не приходили дважды.
     */
    private fun startNetworkMonitor() {
        stopNetworkMonitor()
        networkMonitor = NetworkMonitor(this, ::onNetworkChanged)
            .also { monitor -> monitor.start() }
    }

    private fun stopNetworkMonitor() {
        networkMonitor?.stop()
        networkMonitor = null
    }

    /**
     * Сеть сменилась: исходящие сессии ядра мертвы, пересобираем их через
     * [ProxyEngine.reconnect]. Локальный порт остаётся открытым, поэтому
     * Telegram сам подключится заново — ни уведомления «Запуск…», ни перезапуска
     * сервиса не нужно.
     */
    private fun onNetworkChanged(reason: String, description: String) {
        scope.launch {
            val state = withContext(Dispatchers.IO) { ProxyEngine.snapshot() }
            if (!state.isActive) {
                Log.i(TAG, "сеть сменилась ($reason), но прокси не работает — пропускаем")
                return@launch
            }
            val result = withContext(Dispatchers.IO) { ProxyEngine.reconnect(reason) }
            if (result.ok) {
                Log.i(TAG, "переподключение после смены сети ($reason): " +
                    "оборвано клиентов ${result.clients}, пул H2 пересоздан ${result.h2}")
                updateNotification(getString(R.string.notif_network_change),
                    getString(R.string.notif_reconnecting, description))
            } else {
                Log.w(TAG, "переподключение после смены сети не удалось: ${result.error}")
                updateNotification(getString(R.string.notif_network_change),
                    result.message(this@ProxyService))
            }
        }
    }

    // -- уведомление ---------------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val state = withContext(Dispatchers.IO) { ProxyEngine.snapshot() }
                updateNotification(title(state), text(state))
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun title(state: ProxyUiState): String = when (state.state) {
        ProxyUiState.STATE_RUNNING -> getString(R.string.notif_running)
        ProxyUiState.STATE_STARTING -> getString(R.string.notif_starting)
        ProxyUiState.STATE_STOPPING -> getString(R.string.notif_stopping)
        ProxyUiState.STATE_ERROR -> getString(R.string.notif_error)
        else -> getString(R.string.notif_stopped)
    }

    private fun text(state: ProxyUiState): String = when {
        // Текст ошибки приходит из ядра ключом перевода — раскрываем его здесь.
        state.state == ProxyUiState.STATE_ERROR -> state.errorMessage(this)
        state.state == ProxyUiState.STATE_RUNNING ->
            getString(R.string.notif_running_text, state.address,
                state.stats.inbound, state.stats.outbound)
        else -> if (state.address.isNotEmpty()) state.address else "…"
    }

    private fun updateNotification(title: String, text: String) {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification(title, text))
        // уведомление foreground-сервиса обновляем через startForeground
        if (!startForegroundCompat(notification(title, text)) && !shuttingDown) {
            // Система передумала нас держать (обычно — исчерпан лимит dataSync
            // после многих часов работы). Сворачиваемся сами, пока процесс не
            // убили вместе с прокси.
            notifySystemStopped()
            scope.launch { shutdown(keepDesired = true) }
        }
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    /**
     * Показать уведомление foreground-сервиса. Возвращает false, если система
     * отказала: на Android 12+ это `ForegroundServiceStartNotAllowedException`
     * (старт из фона), на Android 15 — ещё и исчерпанный суточный лимит
     * `dataSync`. Неперехваченное исключение здесь роняет процесс, поэтому
     * ловим и сообщаем наверх.
     */
    private fun startForegroundCompat(note: Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, note, foregroundServiceType())
        inForeground = true
        true
    } catch (exc: Exception) {
        Log.e(TAG, "startForeground отклонён системой", exc)
        false
    }

    /**
     * Тип foreground-сервиса под версию системы — в манифесте объявлены оба
     * (`dataSync|specialUse`), иначе Android 14+ бросает
     * `MissingForegroundServiceTypeException`.
     *
     * * Android 14+ — `specialUse`: прокси должен работать сутками, а `dataSync`
     *   на Android 15+ разрешён лишь ~6 часов в сутки, после чего система зовёт
     *   [onTimeout] и гасит сервис (именно так выглядел «вылет» после долгой
     *   работы);
     * * Android 10…13 — `dataSync`: `specialUse` там ещё не существует, а лимита
     *   по времени нет;
     * * Android 9 и ниже — типов нет вовсе.
     */
    private fun foregroundServiceType(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    /**
     * Отдельное уведомление на случай, когда прокси остановила система, а не
     * пользователь: foreground-уведомление сейчас исчезнет, а причину и способ
     * вернуть прокси нужно показать.
     */
    private fun notifySystemStopped() {
        val text = getString(R.string.notif_system_stopped_text)
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_system_stopped_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?.notify(SYSTEM_STOP_NOTIFICATION_ID, note)
    }

    private fun createChannel() {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        manager?.createNotificationChannel(channel)
    }

    // -- wakelock ------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        // PARTIAL_WAKE_LOCK: держим процесс живым с погашенным экраном.
        // Освобождаем в onDestroy() и при штатной остановке.
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "TGWS"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1001
        private const val SYSTEM_STOP_NOTIFICATION_ID = 1002
        private const val WAKELOCK_TAG = "tgws:proxy"
        private const val POLL_INTERVAL_MS = 2000L

        const val ACTION_START = "org.debug.tgwsproxy.action.START"
        const val ACTION_STOP = "org.debug.tgwsproxy.action.STOP"
        const val ACTION_RESTART = "org.debug.tgwsproxy.action.RESTART"

        /**
         * Все три функции возвращают пустую строку при успехе или текст ошибки
         * для пользователя — НИКОГДА не бросаются.
         *
         * Почему это важно: `startForegroundService()` на Android 12+ бросает
         * `ForegroundServiceStartNotAllowedException` (старт из фона, а на части
         * прошивок Android 14 — даже по тапу на плитку быстрых настроек), а
         * `startService()` из фона на Android 8+ — `IllegalStateException`.
         * Неперехваченное исключение убивает процесс: внешне это выглядит как
         * «нажал плитку — приложение вылетело».
         */

        /** Запустить прокси (сервис поднимется, если не запущен). */
        fun start(context: Context): String =
            send(context, ACTION_START, R.string.what_start)

        /** Перезапустить с новыми настройками (кнопка «Сохранить»). */
        fun restart(context: Context): String =
            send(context, ACTION_RESTART, R.string.what_restart)

        /**
         * Остановить прокси и погасить сервис. Сначала обычный `startService`
         * (так делает кнопка «Остановить» в уведомлении); если он запрещён из
         * фона — как foreground-сервис: сервис к этому моменту и так foreground,
         * поэтому контракт «startForeground за 5 секунд» не нарушается.
         */
        fun stop(context: Context): String {
            val intent = intent(context, ACTION_STOP)
            return try {
                context.startService(intent)
                ""
            } catch (exc: Exception) {
                Log.w(TAG, "startService из фона не сработал, пробую foreground", exc)
                try {
                    ContextCompat.startForegroundService(context, intent)
                    ""
                } catch (second: Exception) {
                    Log.e(TAG, "не удалось остановить прокси", second)
                    describe(context, second, R.string.what_stop)
                }
            }
        }

        private fun send(context: Context, action: String, whatRes: Int): String = try {
            ContextCompat.startForegroundService(context, intent(context, action))
            ""
        } catch (exc: Exception) {
            Log.e(TAG, "система не дала выполнить $action", exc)
            describe(context, exc, whatRes)
        }

        /**
         * Имена исключений, которыми Android запрещает фоновый старт сервиса.
         * Сравниваем строкой, а не `is`: класса
         * `ForegroundServiceStartNotAllowedException` нет на API < 31, и проверка
         * типов на старом устройстве сама упала бы с NoClassDefFoundError.
         */
        private val START_DENIED = setOf(
            "android.app.ForegroundServiceStartNotAllowedException",
            "android.app.BackgroundServiceStartNotAllowedException",
        )

        /**
         * Текст отказа для пользователя.
         *
         * @param whatRes что мы пытались сделать (`R.string.what_start` и т.п.) —
         *        подставляется в перевод первым аргументом
         */
        private fun describe(context: Context, exc: Throwable, whatRes: Int): String {
            val what = context.getString(whatRes)
            val detail = exc.message ?: exc.javaClass.simpleName
            return when {
                exc.javaClass.name in START_DENIED ->
                    context.getString(R.string.error_start_denied, what)
                exc is SecurityException ->
                    context.getString(R.string.error_no_permission, what, detail)
                else -> context.getString(R.string.error_generic, what, detail)
            }
        }

        private fun intent(context: Context, action: String) =
            Intent(context, ProxyService::class.java).setAction(action)
    }
}
