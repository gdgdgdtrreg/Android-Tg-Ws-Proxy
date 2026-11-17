// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy

import com.chaquo.python.android.PyApplication

/**
 * Точка входа приложения.
 *
 * Наследуемся от [PyApplication]: Chaquopy поднимает Python-интерпретатор
 * внутри процесса приложения, поэтому и Activity (UI), и Service (фон) могут
 * обращаться к ядру через [ProxyEngine] — без файлов-команд, «намерений» и
 * watchdog-ов, которые были нужны в Kivy/p4a версии.
 */
class App : PyApplication()
