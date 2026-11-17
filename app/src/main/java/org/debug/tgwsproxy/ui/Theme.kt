// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Палитра и размеры перенесены 1:1 из Kivy-версии (main.py), чтобы нативный UI
 * выглядел как прежний — цвета и радиусы там проверялись тестами.
 */
object Palette {
    val Bg = Color(0xFF1E1E1E)          // фон приложения
    val Card = Color(0xFF2B2B2B)        // карточки и кнопки
    val Field = Color(0xFF232323)       // поля ввода
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0xFF9E9E9E)       // подписи, второстепенный текст
    val Accent = Color(0xFF3390EC)      // синий Telegram
    val Good = Color(0xFF00FF00)        // «подключено»
    val Wait = Color(0xFFFFB300)        // «запускается»
    val Bad = Color(0xFFE53935)         // ошибка
    val Off = Color(0xFF3A3A3A)         // выключенный круг
    val Hint = Color(0xFFC9A227)        // предупреждения
}

object Dimens {
    val RadiusButton = 14.dp
    val RadiusCard = 16.dp
    val ButtonHeight = 52.dp
    val ScreenPadding = 18.dp
}

@Composable
fun TgwsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Color.White,
            background = Palette.Bg,
            onBackground = Palette.Text,
            surface = Palette.Card,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Field,
            onSurfaceVariant = Palette.Muted,
            error = Palette.Bad,
        ),
        content = content,
    )
}
