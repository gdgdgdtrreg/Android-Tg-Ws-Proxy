// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy

import android.content.Context
import android.util.Log

/**
 * Сообщение для пользователя: ключ перевода и аргументы.
 *
 * Зачем ключ, а не готовый текст:
 *
 *  * строки интерфейса живут в `res/values/strings.xml` (русский, язык по
 *    умолчанию) и `res/values-en/strings.xml`, а не размазаны по коду;
 *  * часть сообщений приходит из Python-ядра — мост отдаёт `error_key` и
 *    `error_args` (см. `app/src/main/python/proxy_runner.py`), а перевод
 *    подставляет Kotlin. Ядро не знает про ресурсы Android, поэтому словарь
 *    один и находится здесь;
 *  * текст на русском в поле `error` при этом остаётся и используется как
 *    запасной: для logcat и на случай неизвестного ключа.
 *
 * @param key имя строки в `values/strings.xml`, пустая строка — «сообщения нет»
 * @param args аргументы для плейсхолдеров `%1$s`, `%2$d` — в том порядке, в
 *        котором их подставляет перевод
 */
data class MessageKey(val key: String, val args: List<String> = emptyList()) {

    val isEmpty: Boolean get() = key.isEmpty()

    companion object {
        /** «Сообщения нет» — там, где раньше была пустая строка. */
        val NONE = MessageKey("")
    }
}

/**
 * Ключ -> строковый ресурс.
 *
 * Ключи совпадают с теми, что отдаёт Python (`error_key`), поэтому словарь
 * один на два источника сообщений. Имя ресурса и имя ключа держим одинаковыми:
 * так по тексту ошибки в багрепорте сразу видно, где она собрана.
 */
private val RESOURCES: Map<String, Int> = mapOf(
    // проверка настроек перед стартом (Kotlin)
    "error_port_range" to R.string.error_port_range,
    "error_dc_line" to R.string.error_dc_line,
    // система не дала поднять сервис (Kotlin)
    "error_start_denied" to R.string.error_start_denied,
    "error_no_permission" to R.string.error_no_permission,
    "error_generic" to R.string.error_generic,
    // мост в Python недоступен (Kotlin)
    "error_python_unavailable" to R.string.error_python_unavailable,
    "error_stop_failed" to R.string.error_stop_failed,
    // ключи из Python-ядра (proxy_runner.py)
    "error_settings_json" to R.string.error_settings_json,
    "error_settings_not_object" to R.string.error_settings_not_object,
    "error_secret_hex" to R.string.error_secret_hex,
    "error_dc_entry" to R.string.error_dc_entry,
    "error_dc_list" to R.string.error_dc_list,
    "error_core_start" to R.string.error_core_start,
    "error_port_dead" to R.string.error_port_dead,
    "error_core_crash" to R.string.error_core_crash,
    "error_stop_timeout" to R.string.error_stop_timeout,
    "error_stopped_note" to R.string.error_stopped_note,
    "error_not_running" to R.string.error_not_running,
    "error_reconnect" to R.string.error_reconnect,
)

private const val TAG = "TGWS"

/**
 * Собрать текст сообщения по ключу.
 *
 * @param fallback что показать, если ключ пустой или неизвестен (обычно это
 *        русский текст из ядра — он всегда приложен к ответу)
 *
 * Исключением не бросается: аргументы приходят из Python, и несовпадение числа
 * плейсхолдеров не должно ронять интерфейс — показываем запасной текст.
 */
fun Context.localize(message: MessageKey?, fallback: String = ""): String {
    if (message == null || message.isEmpty) return fallback
    val id = RESOURCES[message.key] ?: return fallback.ifEmpty { message.key }
    return try {
        if (message.args.isEmpty()) {
            getString(id)
        } else {
            getString(id, *message.args.toTypedArray())
        }
    } catch (exc: Exception) {
        Log.w(TAG, "не удалось собрать сообщение ${message.key}", exc)
        fallback.ifEmpty { message.key }
    }
}

/** То же для пары «ключ + аргументы», приходящей из Python-моста. */
fun Context.localize(key: String, args: List<String>, fallback: String): String =
    localize(MessageKey(key, args), fallback)
