// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Плитка «Быстрых настроек» (шторка): включает и выключает прокси одним тапом,
 * без открытия приложения.
 *
 * Плитка намеренно живёт в ТОМ ЖЕ процессе, что и [ProxyService]
 * (`android:process` в манифесте не указан): состояние берётся напрямую из
 * Python-ядра через [ProxyEngine.snapshot] — то есть плитка показывает ровно то
 * же, что главный экран и уведомление сервиса.
 *
 * Опрос ядра идёт только между [onStartListening] и [onStopListening], то есть
 * пока шторка открыта; в фоне плитка не тратит ни процессор, ни батарею —
 * SystemUI просто держит последнее нарисованное состояние.
 *
 * Как добавить плитку (сама она в панель не попадает — добавляет только
 * пользователь): в приложении «Настройки» → секция «Плитка в шторке» →
 * «Добавить плитку» (системный диалог `requestAddTileService`, Android 13+),
 * либо вручную через редактор шторки: карандаш → перетащить «TG-Proxy» наверх.
 *
 * Про Android 14: на части прошивок старт foreground-сервиса по тапу на плитку
 * запрещён (`ForegroundServiceStartNotAllowedException`; в Android 15 это
 * исправлено). Поэтому старт обёрнут в try/catch, а запасной путь — открыть
 * приложение с флагом [MainActivity.EXTRA_START_PROXY]: из видимой Activity
 * сервис поднимается всегда.
 */
class ProxyTileService : TileService() {

    // Главная нить: qsTile.updateTile() и Toast положено вызывать из неё,
    // а поход в Python уводит withContext(Dispatchers.IO) в readState().
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    /** Привязаны ли мы сейчас к SystemUI (между onStartListening/onStopListening). */
    @Volatile
    private var listening = false

    /** Последнее прочитанное состояние: рисуем сразу, пока Python не ответил. */
    @Volatile
    private var lastState: ProxyUiState? = null

    // -- жизненный цикл плитки ------------------------------------------------

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        // Сначала рисуем то, что известно без Python, — плитка показывается
        // мгновенно, а точное состояние дорисует первый же тик опроса.
        render(lastState ?: guessState())
        startPolling()
    }

    override fun onStopListening() {
        listening = false
        pollJob?.cancel()
        pollJob = null
        super.onStopListening()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        render(lastState ?: guessState())
        scope.launch { render(readState()) }
    }

    override fun onTileRemoved() {
        listening = false
        pollJob?.cancel()
        pollJob = null
        lastState = null
        super.onTileRemoved()
    }

    override fun onClick() {
        super.onClick()
        val known = lastState
        if (known != null) {
            toggle(start = !known.isActive)
        } else {
            // Состояние ещё не прочитано (плитку только что привязали): читаем
            // ядро и решаем по факту, иначе тап может пойти не в ту сторону.
            scope.launch { toggle(start = !readState().isActive) }
        }
    }

    override fun onDestroy() {
        listening = false
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // -- переключение ---------------------------------------------------------

    /** [start] = true — поднять прокси, false — погасить. */
    private fun toggle(start: Boolean) {
        if (start) startProxy() else stopProxy()
        // Опрос перезапускаем, чтобы плитка обновилась сразу, а не через тик.
        startPolling()
    }

    private fun startProxy() {
        val store = SettingsStore(this)
        val settings = store.settingsWithSecret()
        val problem = ProxySettings.validate(settings)
        if (problem != null) {
            // С такими настройками ядро не стартует — править их можно только
            // в приложении, поэтому тап открывает главный экран.
            toast(localize(problem))
            openApp(startProxy = false)
            return
        }
        // ProxyService.start() не бросается, а возвращает текст отказа: на
        // части прошивок Android 14 старт foreground-сервиса из шторки запрещён,
        // на Android 15 добавляется ещё и суточный лимит dataSync.
        val failure = ProxyService.start(this)
        if (failure.isNotEmpty()) {
            Log.w(TAG, "не удалось запустить прокси из плитки: $failure")
            toast(failure)
            // Запасной путь: из видимой Activity сервис стартует всегда.
            openApp(startProxy = true)
        }
    }

    private fun stopProxy() {
        // Обойти запрет startService() из фона ProxyService.stop() умеет сам.
        val failure = ProxyService.stop(this)
        if (failure.isNotEmpty()) {
            Log.e(TAG, "не удалось остановить прокси из плитки: $failure")
            toast(failure)
        }
    }
    /** Запасной путь: открыть приложение (из него сервис стартует всегда). */
    private fun openApp(startProxy: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_START_PROXY, startProxy)
        if (isLocked) {
            // На заблокированном экране шторке не разрешено открывать Activity:
            // просим систему сначала разблокировать устройство.
            unlockAndRun { launchApp(intent) }
        } else {
            launchApp(intent)
        }
    }

    private fun launchApp(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // startActivityAndCollapse(Intent) на API 34+ бросает
                // UnsupportedOperationException — там нужен PendingIntent.
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (exc: Exception) {
            Log.w(TAG, "не удалось открыть приложение из плитки", exc)
            try {
                startActivity(intent)
            } catch (second: Exception) {
                Log.e(TAG, "приложение открыть не получилось", second)
            }
        }
    }

    // -- состояние и отрисовка ------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                render(readState())
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun readState(): ProxyUiState {
        val state = withContext(Dispatchers.IO) { ProxyEngine.snapshot() }
        lastState = state
        return state
    }

    private fun render(state: ProxyUiState) {
        // Плитку могли разпривязать, пока корутина дочитывала состояние из
        // Python: после onStopListening() обновлять её уже нельзя.
        if (!listening) return
        val tile = qsTile ?: return
        // Подсвечена, пока прокси включён или переходит между состояниями —
        // тот же признак isActive, по которому тап решает «включить/выключить».
        // Ошибку показываем НЕ как UNAVAILABLE: тогда плитка осталась бы
        // некликабельной и повторить запуск тапом было бы нельзя.
        tile.state = if (state.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle(state)
        }
        try {
            tile.updateTile()
        } catch (exc: Exception) {
            // На части прошивок SystemUI бросается, если плитка уже не видна.
            // Состояние прокси от этого не меняется — ронять процесс незачем.
            Log.w(TAG, "не удалось обновить плитку", exc)
        }
    }

    private fun subtitle(state: ProxyUiState): String = when (state.state) {
        ProxyUiState.STATE_RUNNING ->
            if (state.address.isNotEmpty()) state.address
            else getString(R.string.tile_running)
        ProxyUiState.STATE_STARTING -> getString(R.string.tile_starting)
        ProxyUiState.STATE_STOPPING -> getString(R.string.tile_stopping)
        // Текст ошибки приходит из ядра ключом перевода — раскрываем его здесь,
        // иначе в подписи плитки остался бы русский даже на английском устройстве.
        ProxyUiState.STATE_ERROR ->
            getString(R.string.tile_error, shorten(state.errorMessage(this)))
        else ->
            if (state.address.isNotEmpty()) {
                getString(R.string.tile_off_address, state.address)
            } else {
                getString(R.string.tile_off)
            }
    }

    /**
     * Состояние «на глазок», пока Python не ответил: берём флаг «пользователь
     * хочет, чтобы прокси работал» (сервис ставит его при старте и снимает при
     * остановке) и адрес из настроек.
     */
    private fun guessState(): ProxyUiState {
        val store = SettingsStore(this)
        val settings = store.settings
        return ProxyUiState(
            state = if (store.desiredRunning) {
                ProxyUiState.STATE_STARTING
            } else {
                ProxyUiState.STATE_STOPPED
            },
            host = settings.ip,
            port = settings.port,
        )
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /** Одна строка без хвоста — в подпись плитки много не влезает. */
    private fun shorten(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val value = if (line.isEmpty()) getString(R.string.tile_start_failed) else line
        return if (value.length <= MAX_SUBTITLE) value else value.take(MAX_SUBTITLE - 1) + "…"
    }

    private companion object {
        const val TAG = "TGWS"
        const val POLL_INTERVAL_MS = 1000L
        const val MAX_SUBTITLE = 40
    }
}
