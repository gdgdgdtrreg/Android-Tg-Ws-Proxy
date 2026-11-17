// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy

import android.content.Context
import java.io.File

/**
 * Лог ядра: где он лежит и как прочитать его без root.
 *
 * Пишет файл Python-сторона (`_setup_logging` в `proxy_runner.py`), а папку ей
 * передаёт сервис: [dir] — это ВНЕШНЯЯ приватная папка приложения,
 * `/storage/emulated/0/Android/data/<пакет>/files`. Разрешение на хранилище для
 * неё не нужно ни на одной версии Android (она своя для приложения). Свежие
 * строки видны прямо в «Диагностике», «Копировать лог» кладёт их в буфер обмена,
 * а файл целиком забирают так:
 *
 *  * `adb pull /sdcard/Android/data/<пакет>/files/proxy_log.txt`;
 *  * файловым менеджером на Android 10 и ниже. На Android 11+ система не пускает
 *    чужие приложения в `Android/data`.
 *
 * Раньше Python брал папку из `os.path.expanduser('~')`, а Chaquopy ставит HOME
 * в `/data/user/0/<пакет>/files` — внутреннюю область, которую без root не
 * прочитать. Отсюда и впечатление, что `proxy_log.txt` «не пишется».
 */
object ProxyLog {

    /** Имя файла лога — то же, что в `proxy_runner.LOG_FILE_NAME`. */
    const val FILE_NAME = "proxy_log.txt"

    /**
     * Папка для лога. `getExternalFilesDir(null)` иногда возвращает null
     * (внешнее хранилище отключено/эмулятор без sdcard) — тогда пишем во
     * внутреннюю папку: лог останется в приложении, но прочитать его можно
     * будет только в «Диагностике» и через «Копировать лог».
     */
    fun dir(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /** Полный путь `proxy_log.txt` — показываем его в диагностике. */
    fun file(context: Context): File = File(dir(context), FILE_NAME)
}
