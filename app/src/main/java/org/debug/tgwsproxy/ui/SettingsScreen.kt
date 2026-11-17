// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.debug.tgwsproxy.ProxyLogInfo
import org.debug.tgwsproxy.ProxySettings
import org.debug.tgwsproxy.ProxyUiState
import org.debug.tgwsproxy.R

/**
 * Экран настроек: сворачиваемые секции (как в Kivy-версии) + «Сохранить».
 *
 * @param initial текущие настройки
 * @param state состояние прокси (для секции «Диагностика»)
 * @param onSave нажата кнопка «Сохранить» (сервис перезапустит ядро, если нужно)
 * @param tileRequestSupported система умеет добавлять плитку по запросу
 *        (Android 13+); на старых версиях показываем только инструкцию
 * @param errorText текст ошибки ядра, уже раскрытый из ключа перевода
 *        (см. [org.debug.tgwsproxy.ProxyUiState.errorMessage])
 * @param log последние строки лога ядра и путь к `proxy_log.txt`
 *        (см. [org.debug.tgwsproxy.ProxyLog])
 * @param onCopyLog нажата «Копировать лог» (строки лога в буфер обмена)
 * @param onAddTile нажата кнопка «Добавить плитку»
 */
@Composable
fun SettingsScreen(
    initial: ProxySettings,
    state: ProxyUiState,
    errorText: String,
    log: ProxyLogInfo,
    onBack: () -> Unit,
    onSave: (ProxySettings) -> Unit,
    onBatterySettings: () -> Unit,
    onCopyLog: () -> Unit,
    onAddTile: () -> Unit,
    tileRequestSupported: Boolean,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var settings by remember { mutableStateOf(initial) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var bufferText by remember { mutableStateOf(initial.buffer.toString()) }
    var poolText by remember { mutableStateOf(initial.pool.toString()) }

    var openConnection by rememberSaveable { mutableStateOf(true) }
    var openRouting by rememberSaveable { mutableStateOf(false) }
    var openCf by rememberSaveable { mutableStateOf(false) }
    var openPerf by rememberSaveable { mutableStateOf(false) }
    var openDiagnostics by rememberSaveable { mutableStateOf(false) }
    var openTile by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TgwsButton(
                text = stringResource(R.string.btn_back),
                color = Palette.Card,
                modifier = Modifier.width(96.dp),
                onClick = onBack,
            )
            Text(stringResource(R.string.settings_title), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = Palette.Text,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp))
        }
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionCard(
                title = stringResource(R.string.section_connection),
                expanded = openConnection,
                onToggle = { openConnection = !openConnection },
            ) {
                FieldLabel(stringResource(R.string.field_ip_port))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TgwsField(
                        value = settings.ip,
                        onValueChange = { settings = settings.copy(ip = it) },
                        modifier = Modifier.weight(1f),
                    )
                    TgwsField(
                        value = portText,
                        onValueChange = {
                            portText = it.filter { c -> c.isDigit() }.take(5)
                            settings = settings.copy(port = portText.toIntOrNull() ?: 0)
                        },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.width(120.dp),
                    )
                }

                SwitchRow(stringResource(R.string.switch_allow_lan),
                    settings.allowLan) { settings = settings.copy(allowLan = it) }

                FieldLabel(stringResource(R.string.field_secret))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TgwsField(
                        value = settings.secret,
                        onValueChange = { settings = settings.copy(secret = it) },
                        modifier = Modifier.weight(1f),
                    )
                    // Как в оригинале для Windows: квадратная кнопка в цвете акцента
                    // с круговой стрелкой. Раньше тут была кнопка цвета карточки —
                    // она сливалась с фоном и выглядела простой надписью «Gen».
                    TgwsIconButton(
                        iconRes = R.drawable.ic_refresh,
                        contentDescription = stringResource(R.string.cd_generate_secret),
                        color = Palette.Accent,
                        modifier = Modifier.size(56.dp),
                        glyph = 26.dp,
                        onClick = {
                            settings = settings.copy(secret = ProxySettings.generateSecret())
                        },
                    )
                }

                FieldLabel(stringResource(R.string.field_fake_tls))
                TgwsField(
                    value = settings.fakeTlsDomain,
                    onValueChange = { settings = settings.copy(fakeTlsDomain = it) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(
                title = stringResource(R.string.section_routing),
                expanded = openRouting,
                onToggle = { openRouting = !openRouting },
            ) {
                FieldLabel(stringResource(R.string.field_dcs_hint))
                TgwsField(
                    value = settings.dcs,
                    onValueChange = { settings = settings.copy(dcs = it) },
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                )
                SwitchRow(stringResource(R.string.switch_force_test_dc),
                    settings.forceTestDc) { settings = settings.copy(forceTestDc = it) }
            }

            SectionCard(
                title = stringResource(R.string.section_cf),
                expanded = openCf,
                onToggle = { openCf = !openCf },
            ) {
                SwitchRow(stringResource(R.string.switch_cf_proxy),
                    settings.cfProxyEnable) { settings = settings.copy(cfProxyEnable = it) }
                FieldLabel(stringResource(R.string.field_cf_domains))
                TgwsField(
                    value = settings.cfProxyDomain,
                    onValueChange = { settings = settings.copy(cfProxyDomain = it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldLabel(stringResource(R.string.field_cf_worker))
                TgwsField(
                    value = settings.cfWorkerDomain,
                    onValueChange = { settings = settings.copy(cfWorkerDomain = it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(stringResource(R.string.switch_h2_media),
                    settings.h2Media) { settings = settings.copy(h2Media = it) }
                SwitchRow(stringResource(R.string.switch_no_secure),
                    settings.noSecure) { settings = settings.copy(noSecure = it) }
            }

            SectionCard(
                title = stringResource(R.string.section_perf),
                expanded = openPerf,
                onToggle = { openPerf = !openPerf },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel(stringResource(R.string.field_buffer))
                        TgwsField(
                            value = bufferText,
                            onValueChange = {
                                bufferText = it.filter { c -> c.isDigit() }.take(5)
                                settings = settings.copy(
                                    buffer = bufferText.toIntOrNull() ?: 0)
                            },
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        FieldLabel(stringResource(R.string.field_pool))
                        TgwsField(
                            value = poolText,
                            onValueChange = {
                                poolText = it.filter { c -> c.isDigit() }.take(2)
                                settings = settings.copy(
                                    pool = poolText.toIntOrNull() ?: 0)
                            },
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                SwitchRow(stringResource(R.string.switch_verbose), settings.verbose) {
                    settings = settings.copy(verbose = it)
                }
                // Переключатель применяется при старте ядра: «Сохранить»
                // перезапускает работающий прокси, и лог начинает писаться по-
                // новому. Сам файл `proxy_log.txt` пишется всегда — раньше здесь
                // был второй переключатель, «Писать лог в файл», но место и так
                // ограничено ротацией (1 МБ + копия), а включённый заранее лог
                // помогает разобраться с проблемой, которую уже не повторить.
                FieldLabel(stringResource(R.string.hint_log_applies))
            }

            SectionCard(
                title = stringResource(R.string.section_diagnostics),
                expanded = openDiagnostics,
                onToggle = { openDiagnostics = !openDiagnostics },
            ) {
                Text(
                    text = diagnosticsText(state, errorText, log),
                    fontSize = 14.sp,
                    color = Palette.Muted,
                )
                LogPreview(log)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TgwsButton(stringResource(R.string.btn_copy_log), Palette.Card,
                        modifier = Modifier.weight(1f), onClick = onCopyLog)
                    TgwsButton(stringResource(R.string.btn_battery), Palette.Card,
                        modifier = Modifier.weight(1f), onClick = onBatterySettings)
                }
            }

            SectionCard(
                title = stringResource(R.string.section_tile),
                expanded = openTile,
                onToggle = { openTile = !openTile },
            ) {
                Text(
                    text = stringResource(
                        if (tileRequestSupported) R.string.tile_help_request
                        else R.string.tile_help_manual,
                    ),
                    fontSize = 14.sp,
                    color = Palette.Muted,
                )
                if (tileRequestSupported) {
                    Spacer(Modifier.height(8.dp))
                    TgwsButton(
                        text = stringResource(R.string.btn_add_tile),
                        color = Palette.Card,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onAddTile,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            TgwsButton(
                text = stringResource(R.string.btn_save),
                color = Palette.Accent,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSave(settings) },
            )
            // «О приложении» живёт в настройках, а не на главном экране: там
            // ему место (лицензии и версии — это про настройки/о программе),
            // и главный экран остаётся таким, каким был.
            Spacer(Modifier.height(10.dp))
            TgwsButton(
                text = stringResource(R.string.btn_about),
                color = Palette.Card,
                modifier = Modifier.fillMaxWidth(),
                onClick = onAbout,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun diagnosticsText(
    state: ProxyUiState,
    errorText: String,
    log: ProxyLogInfo,
): String {
    val lines = mutableListOf<String>()
    lines += when (state.state) {
        ProxyUiState.STATE_RUNNING ->
            stringResource(R.string.diag_status_running, state.address)
        ProxyUiState.STATE_STARTING -> stringResource(R.string.diag_status_starting)
        ProxyUiState.STATE_STOPPING -> stringResource(R.string.diag_status_stopping)
        // errorText приходит уже переведённым: ошибка ядра — это ключ, а
        // раскрывает его Activity (см. ProxyUiState.errorMessage).
        ProxyUiState.STATE_ERROR -> stringResource(R.string.diag_status_error, errorText)
        else -> stringResource(R.string.diag_status_stopped)
    }
    if (state.coreVersion.isNotBlank()) {
        lines += stringResource(R.string.diag_core_version, state.coreVersion)
    }
    if (state.uptime > 0) lines += stringResource(R.string.diag_uptime, state.uptime)
    if (state.stats.total > 0) {
        lines += stringResource(R.string.diag_connections, state.stats.total,
            state.stats.active, state.stats.errors)
        lines += stringResource(R.string.diag_traffic, state.stats.inbound,
            state.stats.outbound)
    }
    // «Логов пока нет» показываем ТОЛЬКО когда строк действительно нет: превью
    // ниже тогда пустое, и подпись объясняет почему. Есть строки — они сами себя
    // показывают, и отдельная подпись не нужна (раньше она стояла безусловно и
    // спорила с превью). Строки про путь и размер файла, ошибку чтения и logcat
    // убраны вместе со своими строками перевода по правке пользователя; путь
    // файла неизменен и описан в README: внешняя папка приложения,
    // Android/data/<пакет>/files/proxy_log.txt, забирается adb pull.
    if (!log.hasLines) {
        lines += stringResource(R.string.diag_log_off)
    }
    lines += stringResource(R.string.diag_verbose,
        stringResource(if (log.verbose) R.string.value_on else R.string.value_off))
    return lines.joinToString("\n")
}

/**
 * Последние строки лога прямо в приложении — свежие сверху.
 *
 * До этого лог был виден только в logcat (нужен компьютер) или в файле из
 * приватной папки (нужен root), поэтому переключатель «Подробный лог» выглядел
 * неработающим. Строки берём из [ProxyLogInfo]: файл, а если он выключен —
 * кольцевой буфер процесса.
 */
@Composable
private fun LogPreview(log: ProxyLogInfo) {
    if (!log.hasLines) return
    val shown = log.lines.takeLast(PREVIEW_LINES).reversed()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 190.dp)
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .background(Palette.Field)
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text(
            text = shown.joinToString("\n"),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Palette.Muted,
        )
    }
}

/** Сколько строк показываем в предпросмотре (в буфер «Копировать лог» идёт больше). */
private const val PREVIEW_LINES = 20

// -- мелкие строительные блоки ---------------------------------------------

@Composable
private fun SectionCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .background(Palette.Card),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "–  $title" else "+  $title",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Text,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 14.sp, color = Palette.Muted,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 15.sp, color = Palette.Text,
            modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Text,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.Muted,
                uncheckedTrackColor = Palette.Field,
            ),
        )
    }
}

@Composable
private fun TgwsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 15.sp, color = Palette.Text),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Palette.Field,
                unfocusedContainerColor = Palette.Field,
                focusedBorderColor = Palette.Accent,
                unfocusedBorderColor = Palette.Field,
                cursorColor = Palette.Accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
