// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Следит за сменой сети и говорит, когда исходящие соединения ядра нужно
 * пересобрать: Wi-Fi -> мобильные данные и обратно, пропажа и возврат связи,
 * сон Wi-Fi, вход в captive-портал (сеть есть, а интернета нет).
 *
 * Зачем это нужно: сокеты до DC Telegram и до Cloudflare привязаны к прежней
 * сети и после переключения мертвы, а запись в TCP поверх пропавшей сети может
 * висеть минутами — прокси при этом выглядит «работающим», но ничего не
 * передаёт. Целиком перезапускать ядро не нужно: [ProxyEngine.reconnect]
 * пересобирает только исходящие сессии, локальный порт для Telegram остаётся
 * открытым.
 *
 * События сети приходят пачкой (onLost у старой, onAvailable у новой, затем
 * несколько onCapabilitiesChanged), поэтому реакция отложена на [SETTLE_MS] с
 * ПЕРВОГО сигнала пачки — так переключение «схлопывается» в одно
 * переподключение. Срабатываем только если «отпечаток» сети действительно
 * изменился: иначе ядро пересобиралось бы на каждом обновлении свойств той же
 * самой сети. Если признак «сеть проверена» (VALIDATED) приходит позже окна,
 * переподключение повторится второй раз — это дёшево и безопасно.
 *
 * После [stop] монитор не перезапускают: [ProxyService] создаёт новый.
 */
class NetworkMonitor(
    context: Context,
    private val onChanged: (reason: String, description: String) -> Unit,
) {

    /**
     * @param onChanged причина смены сети двумя вариантами: `reason` —
     *        технический (ASCII, уходит в лог ядра) и `description` — перевод
     *        для уведомления. Языков у приложения два, а лог один, поэтому
     *        строки не смешиваем.
     */
    private val appContext: Context = context.applicationContext

    private val manager =
        ContextCompat.getSystemService(appContext, ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var pendingJob: Job? = null

    @Volatile
    private var pendingSince = 0L

    /** Последняя увиденная сеть — точка отсчёта для сравнения. */
    @Volatile
    private var lastSnapshot: Snapshot? = null

    /**
     * Отпечаток сети: сам объект (netId), тип доступа и «есть ли интернет».
     *
     * @param token тип доступа для лога — стабильный ASCII, не зависит от языка
     * @param labelRes тот же тип для интерфейса — строка из `values/strings.xml`
     */
    private data class Snapshot(
        val network: Network?,
        val token: String,
        val labelRes: Int,
        val validated: Boolean,
    )

    /** Начать следить за сетью. */
    fun start() {
        // Точку отсчёта ставим ДО регистрации: onAvailable для текущей сети
        // приходит сразу же, и это не повод пересобирать соединения.
        lastSnapshot = snapshot()
        if (callback != null) return

        val connectivity = manager ?: run {
            Log.w(TAG, "ConnectivityManager недоступен — слежение за сетью выключено")
            return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val listener = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) = schedule(TRIGGER_AVAILABLE)

            override fun onLost(network: Network) = schedule(TRIGGER_LOST)

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = schedule(TRIGGER_CAPABILITIES)
        }
        try {
            connectivity.registerNetworkCallback(request, listener)
            callback = listener
            Log.i(TAG, "слежение за сетью включено (${technical(lastSnapshot)})")
        } catch (exc: Exception) {
            Log.e(TAG, "не удалось зарегистрировать NetworkCallback", exc)
        }
    }

    /** Перестать следить: снять приёмник и погасить отложенную проверку. */
    fun stop() {
        pendingJob?.cancel()
        pendingJob = null
        pendingSince = 0L
        callback?.let { listener ->
            try {
                manager?.unregisterNetworkCallback(listener)
            } catch (exc: Exception) {
                Log.w(TAG, "NetworkCallback не снялся", exc)
            }
        }
        callback = null
        lastSnapshot = null
        scope.cancel()
    }

    /**
     * Отложить решение на окно [SETTLE_MS] с первого сигнала пачки.
     *
     * @param trigger что произошло (`available`/`lost`/`capabilities`) — идёт
     *        только в logcat, поэтому не переводится
     */
    private fun schedule(trigger: String) {
        val now = SystemClock.elapsedRealtime()
        if (pendingSince == 0L) pendingSince = now
        val wait = (SETTLE_MS - (now - pendingSince)).coerceAtLeast(0L)

        pendingJob?.cancel()
        pendingJob = scope.launch {
            if (wait > 0L) delay(wait)
            pendingJob = null
            pendingSince = 0L

            val previous = lastSnapshot
            val current = snapshot()
            if (current == previous) return@launch
            lastSnapshot = current

            val reason = "${technical(previous)} -> ${technical(current)}"
            val description = appContext.getString(R.string.net_transition,
                describe(previous), describe(current))
            Log.i(TAG, "смена сети: $reason ($trigger)")
            onChanged(reason, description)
        }
    }

    /** Чем сейчас пользуется система; null-сеть — когда связи нет вовсе. */
    private fun snapshot(): Snapshot? {
        val connectivity = manager ?: return null
        return try {
            val network = connectivity.activeNetwork
            val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
            if (network == null || capabilities == null ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return Snapshot(null, TOKEN_NONE, R.string.net_none, false)
            }
            val (token, labelRes) = transport(capabilities)
            Snapshot(
                network = network,
                token = token,
                labelRes = labelRes,
                validated = capabilities
                    .hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        } catch (exc: Exception) {
            Log.w(TAG, "не удалось прочитать состояние сети", exc)
            lastSnapshot      // точку отсчёта при ошибке чтения не меняем
        }
    }

    /** Тип доступа парой: токен для лога и строковый ресурс для интерфейса. */
    private fun transport(capabilities: NetworkCapabilities): Pair<String, Int> = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
            TOKEN_WIFI to R.string.net_wifi
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            TOKEN_CELLULAR to R.string.net_cellular
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
            TOKEN_ETHERNET to R.string.net_ethernet
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
            TOKEN_VPN to R.string.net_vpn
        else -> TOKEN_OTHER to R.string.net_other
    }

    /** Как сеть выглядит в уведомлении (перевод из `values/strings.xml`). */
    private fun describe(snapshot: Snapshot?): String = when {
        snapshot == null -> appContext.getString(R.string.net_unknown)
        snapshot.network == null -> appContext.getString(R.string.net_none)
        snapshot.validated -> appContext.getString(snapshot.labelRes)
        else -> appContext.getString(R.string.net_no_internet,
            appContext.getString(snapshot.labelRes))
    }

    /** Как сеть выглядит в логе ядра: ASCII, одинаково на любом языке. */
    private fun technical(snapshot: Snapshot?): String = when {
        snapshot == null -> TOKEN_UNKNOWN
        snapshot.network == null -> TOKEN_NONE
        snapshot.validated -> snapshot.token
        else -> "${snapshot.token}(no-internet)"
    }

    private companion object {
        const val TAG = "TGWS"

        // Токены для лога (не переводятся) и триггеры событий.
        const val TOKEN_WIFI = "wifi"
        const val TOKEN_CELLULAR = "cellular"
        const val TOKEN_ETHERNET = "ethernet"
        const val TOKEN_VPN = "vpn"
        const val TOKEN_OTHER = "other"
        const val TOKEN_NONE = "none"
        const val TOKEN_UNKNOWN = "unknown"
        const val TRIGGER_AVAILABLE = "available"
        const val TRIGGER_LOST = "lost"
        const val TRIGGER_CAPABILITIES = "capabilities"

        /** Сколько даём сети «успокоиться» с первого сигнала пачки событий. */
        const val SETTLE_MS = 2000L
    }
}
