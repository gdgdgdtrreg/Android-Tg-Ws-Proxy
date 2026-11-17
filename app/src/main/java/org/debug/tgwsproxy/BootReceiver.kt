// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Автозапуск после перезагрузки телефона.
 *
 * В Kivy/p4a-версии это было невозможно (spec не умеет добавлять `<receiver>`),
 * поэтому прокси приходилось поднимать руками. Здесь — обычный приёмник:
 * поднимаем прокси, только если пользователь оставил его включённым
 * (`desiredRunning`), чтобы приложение не запускалось само «из ниоткуда».
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val store = SettingsStore(context)
        if (!store.desiredRunning) {
            Log.i("TGWS", "автозапуск: прокси выключен пользователем, пропускаем")
            return
        }
        Log.i("TGWS", "автозапуск прокси после $action")
        val error = ProxyService.start(context)
        if (error.isNotEmpty()) {
            // Android 12+ может запретить foreground-сервис даже по
            // BOOT_COMPLETED — не роняем процесс при загрузке устройства.
            Log.w("TGWS", "автозапуск не сработал: $error")
        }
    }
}
