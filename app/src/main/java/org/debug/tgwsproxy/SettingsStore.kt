// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy

import android.content.Context

/**
 * Хранилище настроек: SharedPreferences (private-область приложения).
 *
 * В Kivy-версии настройки лежали в `proxy_settings.json` и передавались сервису
 * аргументом; здесь файлов не нужно, но ключи JSON оставлены теми же, чтобы
 * Python-мост и ядро не менялись.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("proxy_settings", Context.MODE_PRIVATE)

    var settings: ProxySettings
        get() = ProxySettings.fromJson(prefs.getString(KEY_SETTINGS, null))
        set(value) {
            prefs.edit().putString(KEY_SETTINGS, value.toJson()).apply()
        }

    /** Пользователь хочет, чтобы прокси работал (для автозапуска после перезагрузки). */
    var desiredRunning: Boolean
        get() = prefs.getBoolean(KEY_DESIRED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DESIRED, value).apply()
        }

    /** Секрет генерируется один раз и дальше переиспользуется. */
    fun settingsWithSecret(): ProxySettings {
        val current = settings
        if (current.secret.isNotEmpty()) return current
        val generated = current.copy(secret = ProxySettings.generateSecret())
        settings = generated
        return generated
    }

    private companion object {
        const val KEY_SETTINGS = "settings_json"
        const val KEY_DESIRED = "desired_running"
    }
}
