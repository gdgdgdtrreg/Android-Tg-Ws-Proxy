// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Круглая кнопка питания — как в прежнем UI: круг + значок, нарисованный
 * линиями (галоглиф «⏻» есть не во всех шрифтах, поэтому рисуем сами).
 *
 * Значения подобраны по замечаниям с телефона (см. историю в проекте
 * tg-ws-proxy-android): значок меньше круга и с ПРЯМЫМИ наконечниками.
 */
private const val GLYPH_SCALE = 0.47f      // радиус значка / радиус круга
private const val GLYPH_STROKE = 0.096f    // толщина линии / радиус круга
private const val BAR_BOTTOM = 0.08f       // низ палочки (доли радиуса значка)
private const val BAR_TOP = 1.25f          // верх палочки: выше разрыва кольца

/** Разрыв кольца вверху: 56°, как в прежнем UI. */
private const val GAP_DEGREES = 56f

@Composable
fun PowerButton(
    stateValue: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill = when (stateValue) {
        "on" -> Palette.Good
        "busy" -> Palette.Wait
        "error" -> Palette.Bad
        else -> Palette.Off
    }
    val glyph = if (stateValue == "off") Palette.Muted else Color.White

    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(color = fill, radius = radius)
            if (radius <= 0f) return@Canvas

            val glyphRadius = radius * GLYPH_SCALE
            val stroke = radius * GLYPH_STROKE
            val center = Offset(size.width / 2f, size.height / 2f)

            // Compose: 0° — вправо, отсчёт по часовой. Разрыв кольца — сверху,
            // поэтому дуга идёт от −(90° − половина разрыва) на 360° − разрыв.
            drawArc(
                color = glyph,
                startAngle = -(90f - GAP_DEGREES / 2f),
                sweepAngle = 360f - GAP_DEGREES,
                useCenter = false,
                topLeft = Offset(center.x - glyphRadius, center.y - glyphRadius),
                size = Size(glyphRadius * 2f, glyphRadius * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            // палочка: от центра вверх, выше разрыва (y растёт вниз)
            drawLine(
                color = glyph,
                start = Offset(center.x, center.y + glyphRadius * BAR_BOTTOM),
                end = Offset(center.x, center.y - glyphRadius * BAR_TOP),
                strokeWidth = stroke,
                cap = StrokeCap.Butt,
            )
        }
    }
}
