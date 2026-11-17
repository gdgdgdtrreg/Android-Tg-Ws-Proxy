// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy

import android.Manifest
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.debug.tgwsproxy.ui.AboutScreen
import org.debug.tgwsproxy.ui.MainScreen
import org.debug.tgwsproxy.ui.Palette
import org.debug.tgwsproxy.ui.SettingsScreen
import org.debug.tgwsproxy.ui.TgwsTheme

/**
 * Единственная Activity: два экрана (главный и настройки) на Compose.
 *
 * Состояние прокси читается опросом [ProxyEngine.snapshot] — сервис и UI живут
 * в одном процессе, поэтому это просто вызов Python-функции, без файлов и
 * широковещательных сообщений.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        maybeStartFromTile(intent)
        setContent {
            TgwsTheme {
                AppRoot(activity = this)
            }
        }
    }

    /**
     * Плитка «Быстрых настроек» прислала нас сюда, потому что система не дала ей
     * поднять foreground-сервис прямо из шторки (так делает часть прошивок
     * Android 14). Из видимой Activity старт разрешён всегда — запускаем прокси
     * сами, и UI ниже подхватит состояние обычным опросом.
     *
     * Activity объявлена `singleTask`, поэтому при уже открытом экране приходим
     * сюда, а не в [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeStartFromTile(intent)
    }

    private fun maybeStartFromTile(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_PROXY, false) != true) return
        // Флаг снимаем: пересоздание экрана (поворот, «не помнить») не должно
        // запускать прокси заново.
        intent.removeExtra(EXTRA_START_PROXY)

        val store = SettingsStore(this)
        // Флаг desiredRunning здесь не советчик: после системного лимита
        // dataSync он остаётся true, хотя прокси погашен. ProxyEngine.start()
        // идемпотентен — если ядро уже работает, второй старт ничего не делает.
        val problem = ProxySettings.validate(store.settingsWithSecret())
        if (problem != null) {
            Toast.makeText(this, localize(problem), Toast.LENGTH_LONG).show()
            return
        }
        // Мы уже в видимой Activity, так что обычно старт разрешён. Но на
        // Android 15 с исчерпанным суточным лимитом dataSync система отказывает
        // даже из foreground — показываем причину вместо падения процесса.
        val startError = ProxyService.start(this)
        if (startError.isNotEmpty()) {
            Toast.makeText(this, startError, Toast.LENGTH_LONG).show()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /**
         * «Плитка просит поднять прокси»: запасной путь для прошивок, где старт
         * foreground-сервиса из шторки запрещён (см. [ProxyTileService]).
         */
        const val EXTRA_START_PROXY = "org.debug.tgwsproxy.extra.START_PROXY"
    }
}

private const val SCREEN_MAIN = "main"
private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_ABOUT = "about"

/** Сколько строк лога показываем в «Диагностике» (опросом). */
private const val LOG_PREVIEW_LINES = 60

@Composable
private fun AppRoot(activity: MainActivity) {
    val store = remember { SettingsStore(activity) }
    var screen by rememberSaveable { mutableStateOf(SCREEN_MAIN) }
    var settings by remember { mutableStateOf(store.settingsWithSecret()) }
    var state by remember { mutableStateOf(ProxyUiState()) }
    var logInfo by remember { mutableStateOf(ProxyLogInfo()) }
    var toast by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Путь к proxy_log.txt знаем сами (Python узнаёт его только после старта, а
    // показать его в диагностике нужно и до старта).
    val logPath = remember { ProxyLog.file(activity).absolutePath }

    // опрос состояния ядра (в фоновом потоке: вызов идёт в Python).
    // Лог подтягиваем только на экране настроек — там есть «Диагностика», и
    // незачем читать файл каждые 1.2 секунды на главном экране.
    LaunchedEffect(screen) {
        while (true) {
            state = withContext(Dispatchers.IO) { ProxyEngine.snapshot() }
            logInfo = if (screen == SCREEN_SETTINGS) {
                withContext(Dispatchers.IO) {
                    ProxyEngine.logInfo(LOG_PREVIEW_LINES)
                }.copy(path = logPath)
            } else {
                ProxyLogInfo(path = logPath)
            }
            delay(1200)
        }
    }
    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) {
            Toast.makeText(activity, toast, Toast.LENGTH_SHORT).show()
            delay(2000)
            toast = ""
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        val content = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(insets)

        if (screen == SCREEN_MAIN) {
            MainScreen(
                state = state,
                errorText = state.errorMessage(activity),
                modifier = content,
                onToggle = {
                    // ProxyService.start/stop возвращают текст ошибки вместо
                    // исключения: падать на глазах пользователя нельзя.
                    toast = if (state.isActive) {
                        ProxyService.stop(activity)
                    } else {
                        val problem = ProxySettings.validate(settings)
                        if (problem != null) activity.localize(problem)
                        else ProxyService.start(activity)
                    }
                },
                onCopy = {
                    if (state.link.isEmpty()) {
                        toast = activity.getString(R.string.toast_link_later)
                    } else {
                        copyToClipboard(activity, state.link)
                        toast = activity.getString(R.string.toast_link_copied)
                    }
                },
                onTelegram = { openInTelegram(activity, state.link) { toast = it } },
                onSettings = { screen = SCREEN_SETTINGS },
                onAbout = { screen = SCREEN_ABOUT },
            )
        } else if (screen == SCREEN_ABOUT) {
            // Экран «О приложении»: версия, GPL-3.0, адрес исходного кода и
            // сторонние компоненты со своими лицензиями.
            AboutScreen(
                state = state,
                modifier = content,
                onBack = { screen = SCREEN_MAIN },
            )
        } else {
            SettingsScreen(
                initial = settings,
                state = state,
                errorText = state.errorMessage(activity),
                log = logInfo,
                modifier = content,
                onBack = { screen = SCREEN_MAIN },
                onSave = { updated ->
                    settings = updated
                    store.settings = updated
                    if (state.isActive) {
                        // настройки применяются только при старте ядра
                        val error = ProxyService.restart(activity)
                        toast = if (error.isNotEmpty()) {
                            error
                        } else {
                            activity.getString(R.string.toast_saved_restarting)
                        }
                    } else {
                        toast = activity.getString(R.string.toast_saved)
                    }
                    screen = SCREEN_MAIN
                },
                onBatterySettings = { requestBatteryExemption(activity) },
                onCopyLog = {
                    if (!logInfo.hasLines) {
                        toast = activity.getString(R.string.toast_log_empty)
                    } else {
                        copyToClipboard(activity, logInfo.text)
                        toast = plural(activity, R.plurals.toast_log_copied,
                            logInfo.lines.size)
                    }
                },
                onAddTile = { requestAddTile(activity) { toast = it } },
                tileRequestSupported =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            )
        }
    }
}

/**
 * Строка с числом: «Скопировано 25 строк лога» — в русском и английском разные
 * формы, поэтому берём перевод из `<plurals>`, а не собираем текст руками.
 */
private fun plural(context: Context, id: Int, count: Int): String =
    context.resources.getQuantityString(id, count, count)

private fun copyToClipboard(context: Context, text: String) {
    val manager = ContextCompat.getSystemService(context, ClipboardManager::class.java)
    manager?.setPrimaryClip(ClipData.newPlainText("tg-ws-proxy", text))
}

private fun openInTelegram(context: Context, link: String, onError: (String) -> Unit) {
    if (link.isEmpty()) {
        onError(context.getString(R.string.error_link_empty))
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        copyToClipboard(context, link)
        onError(context.getString(R.string.error_telegram_missing))
    }
}

/**
 * Просим систему добавить плитку в шторку: `requestAddTileService` показывает
 * системный диалог (Android 13+). Самим положить плитку в панель нельзя —
 * это делает только пользователь, поэтому без запроса ему пришлось бы искать
 * плитку в редакторе шторки.
 *
 * Вызывать нужно из видимой Activity (система отклоняет запросы из фона) —
 * здесь это кнопка в настройках.
 */
private fun requestAddTile(activity: MainActivity, onResult: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        onResult(activity.getString(R.string.tile_need_android13))
        return
    }
    val statusBar = ContextCompat.getSystemService(activity, StatusBarManager::class.java)
    if (statusBar == null) {
        onResult(activity.getString(R.string.tile_no_manager))
        return
    }
    try {
        statusBar.requestAddTileService(
            ComponentName(activity, ProxyTileService::class.java),
            activity.getString(R.string.tile_label),
            Icon.createWithResource(activity, R.drawable.ic_tile_proxy),
            activity.mainExecutor,
        ) { result ->
            // Коды ответа — константы StatusBarManager (API 33+).
            onResult(
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                        activity.getString(R.string.tile_added)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        activity.getString(R.string.tile_already_added)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                        activity.getString(R.string.tile_not_added)
                    else -> activity.getString(R.string.tile_request_rejected, result)
                },
            )
        }
    } catch (exc: Exception) {
        Log.w("TGWS", "не удалось попросить систему добавить плитку", exc)
        onResult(activity.getString(R.string.tile_manual_hint))
    }
}

/** ignore battery optimizations: без этого MIUI/EMUI убивают сервис. */
private fun requestBatteryExemption(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

