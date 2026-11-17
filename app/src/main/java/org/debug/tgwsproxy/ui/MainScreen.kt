// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.debug.tgwsproxy.ProxyUiState
import org.debug.tgwsproxy.R

/**
 * Главный экран: заголовок, круг-кнопка, статус с адресом, подсказка и кнопки —
 * «копировать», «В telegram», «настройки» (макет прежней Kivy-версии) и
 * «о приложении» с лицензиями.
 *
 * Все подписи берутся из `res/values/strings.xml` (перевод — `values-en/`).
 *
 * Правило из прежнего UI: переходные состояния («Запуск…», «Останавливаю…»)
 * показываем ТОЛЬКО крупным статусом, подсказка под адресом остаётся пустой —
 * иначе сообщение дублировалось. Подсказка нужна там, где объясняет причину
 * (ошибка, потеря связи).
 */
@Composable
fun MainScreen(
    state: ProxyUiState,
    errorText: String,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onTelegram: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bigStatus = when (state.state) {
        ProxyUiState.STATE_STARTING -> stringResource(R.string.main_status_starting)
        ProxyUiState.STATE_RUNNING -> stringResource(R.string.main_status_running)
        ProxyUiState.STATE_STOPPING -> stringResource(R.string.main_status_stopping)
        ProxyUiState.STATE_ERROR -> stringResource(R.string.main_status_error)
        else -> stringResource(R.string.main_status_stopped)
    }
    val statusColor = when (state.state) {
        ProxyUiState.STATE_RUNNING -> Palette.Good
        ProxyUiState.STATE_ERROR -> Palette.Bad
        ProxyUiState.STATE_STARTING, ProxyUiState.STATE_STOPPING -> Palette.Accent
        else -> Palette.Text
    }
    // Строки готовим ДО when: лямбда `ifBlank { … }` не composable, и
    // stringResource() внутри неё вызвать нельзя.
    val dash = stringResource(R.string.value_none)
    val serviceDead = stringResource(R.string.main_service_dead)
    val hint = when {
        // errorText приходит из вызывающего кода уже переведённым: ошибка
        // ядра приходит ключом, а Context здесь — не у экрана, а у Activity.
        state.state == ProxyUiState.STATE_ERROR -> errorText.ifBlank { serviceDead }
        state.state == ProxyUiState.STATE_RUNNING && state.stats.total > 0 ->
            stringResource(R.string.main_connections, state.stats.total,
                state.stats.active, state.stats.ws, state.stats.h2, state.stats.cf,
                state.stats.inbound, state.stats.outbound)
        else -> ""
    }
    val powerValue = when {
        state.state == ProxyUiState.STATE_RUNNING -> "on"
        state.state == ProxyUiState.STATE_STARTING ||
            state.state == ProxyUiState.STATE_STOPPING -> "busy"
        state.state == ProxyUiState.STATE_ERROR -> "error"
        else -> "off"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), fontSize = 25.sp,
            fontWeight = FontWeight.Bold, color = Palette.Text)
        Text("${state.coreVersion.ifBlank { "…" }}",
            fontSize = 12.sp, color = Palette.Muted)

        Spacer(Modifier.weight(1f))

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // minOf/maxOf (а не kotlin.math.min/max): те работают только с числами,
            // а Dp — свой тип. Для Dp есть ещё androidx.compose.ui.unit.min/max,
            // но minOf/maxOf из стандартной библиотеки работают без импорта.
            val side = minOf(190.dp, maxOf(132.dp, maxWidth * 0.46f))
            PowerButton(stateValue = powerValue, onClick = onToggle,
                modifier = Modifier.size(side))
        }

        Spacer(Modifier.height(14.dp))

        Text(bigStatus, fontSize = 16.sp, color = statusColor)
        Text(state.address.ifBlank { dash }, fontSize = 13.sp,
            color = Palette.Muted)
        Text(
            text = hint,
            fontSize = 12.sp,
            color = if (state.state == ProxyUiState.STATE_ERROR) Palette.Bad
            else Palette.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, start = 8.dp, end = 8.dp),
        )

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TgwsButton(
                text = stringResource(R.string.btn_copy),
                color = Palette.Card,
                modifier = Modifier.weight(0.44f),
                onClick = onCopy,
            )
            TgwsButton(
                text = stringResource(R.string.btn_telegram),
                color = Palette.Accent,
                modifier = Modifier.weight(0.56f),
                onClick = onTelegram,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TgwsButton(
                text = stringResource(R.string.btn_settings),
                color = Palette.Card,
                modifier = Modifier.weight(1f),
                onClick = onSettings,
            )
            TgwsButton(
                text = stringResource(R.string.btn_about),
                color = Palette.Card,
                modifier = Modifier.weight(1f),
                onClick = onAbout,
            )
        }
    }
}

/** Кнопка в палитре прежнего UI: скруглённые углы, плоский цвет. */
@Composable
fun TgwsButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(Dimens.ButtonHeight),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimens.RadiusButton),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Palette.Text,
            disabledContainerColor = color.copy(alpha = 0.5f),
            disabledContentColor = Palette.Muted,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(text, fontSize = 15.sp)
    }
}

/**
 * Кнопка-иконка в том же оформлении, что и [TgwsButton]: скруглённые углы,
 * плоский цвет. Размер задаёт вызывающий код (`Modifier.size(...)`) — так её
 * можно подогнать под высоту поля ввода.
 */
@Composable
fun TgwsIconButton(
    iconRes: Int,
    contentDescription: String,
    color: Color,
    modifier: Modifier = Modifier,
    glyph: Dp = 24.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.RadiusButton),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Palette.Text,
            disabledContainerColor = color.copy(alpha = 0.5f),
            disabledContentColor = Palette.Muted,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(glyph),
            tint = Palette.Text,
        )
    }
}
