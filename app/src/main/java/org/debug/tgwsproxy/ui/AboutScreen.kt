// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import org.debug.tgwsproxy.ProxyUiState
import org.debug.tgwsproxy.R

/**
 * Экран «О приложении»: версия, лицензия приложения, где взять исходный код и
 * сторонние компоненты со своими лицензиями.
 *
 * Полные тексты лицензий лежат файлами `.txt` в каталоге `res/raw`, а не в
 * `strings.xml`: тексту GPL-3.0 (34 КБ) не нужно экранирование кавычек,
 * апострофов и знаков процента, а переводить юридический текст нельзя — он
 * обязан совпадать с каноническим. Читаются файлы через
 * `resources.openRawResource()` и только когда раздел раскрыт, поэтому в
 * память попадает один текст за раз.
 *
 * Правящему файл: комментарии Kotlin ВЛОЖЕННЫЕ, поэтому внутри комментария
 * нельзя писать слеш со звёздочкой подряд — глоб вида «каталог/» плюс «*.txt»
 * даёт эту пару, открывает вложенный комментарий, и закрывающая пара KDoc
 * закрывает только его: внешний комментарий остаётся открытым и «съедает»
 * весь файл до конца (класс перестаёт существовать для компилятора). Пишите
 * каталог и расширение раздельно, как выше.
 *
 * Для GPL-3.0 этого набора достаточно: лицензия требует передать получателю
 * вместе с объектным кодом текст лицензии, уведомления об авторских правах и
 * способ получить соответствующий исходный код — экран показывает и текст
 * (`res/raw/gpl3.txt`), и адрес репозитория ([SOURCE_URL]).
 */

/**
 * Адрес репозитория с соответствующим исходным кодом (требование GPL-3.0).
 * Не строковый ресурс: это не переводимый текст, а часть лицензионного
 * уведомления, и меняться он должен вместе с публикацией проекта.
 */
private const val SOURCE_URL = "https://github.com/gdgdgdtrreg/Android-Tg-Ws-Proxy"

private const val TAG = "TGWS"

/**
 * Строка списка лицензий.
 *
 * @param name название компонента — имя собственное, не переводится
 * @param details версия и авторские права
 * @param license SPDX-идентификатор лицензии
 * @param rawRes ресурс `res/raw` с полным текстом лицензии; 0 — текста в
 *        приложении нет (он лежит в самом компоненте, см. `notices.txt`)
 * @param url где взять исходный код компонента
 */
private data class LicenseItem(
    val name: String,
    val details: String,
    val license: String,
    val rawRes: Int = 0,
    val url: String = "",
)

/**
 * Компоненты, чей код или данные попадают в APK.
 *
 * Версии Python-пакетов — те, что разрешил pip при проверке сборки; на устройстве
 * Chaquopy может поставить иные минорные версии. Их собственные тексты лицензий и
 * уведомления лежат внутри APK в метаданных пакетов
 * (`<имя>-<версия>.dist-info/LICENSE` в `assets/chaquopy/`), а полный список — в
 * `res/raw/notices.txt` и в `THIRD-PARTY-NOTICES.md` репозитория.
 */
private val COMPONENTS = listOf(
    LicenseItem("Flowseal/tg-ws-proxy", "1.11.2 - Copyright (c) 2026 Flowseal",
        "MIT", R.raw.mit, "https://github.com/Flowseal/tg-ws-proxy"),
    LicenseItem("Chaquopy", "17.0.0 - Copyright (c) 2017-2025 Chaquo Ltd and contributors",
        "MIT", R.raw.mit, "https://chaquo.com/chaquopy/"),
    LicenseItem("httpx", "0.28.1", "BSD-3-Clause", R.raw.bsd3_clause,
        "https://github.com/encode/httpx"),
    LicenseItem("httpcore", "1.0.9", "BSD-3-Clause", R.raw.bsd3_clause,
        "https://github.com/encode/httpcore"),
    LicenseItem("h11", "0.16.0", "MIT", R.raw.mit,
        "https://github.com/python-hyper/h11"),
    LicenseItem("h2", "4.4.1", "MIT", R.raw.mit,
        "https://github.com/python-hyper/h2"),
    LicenseItem("hpack", "4.2.0", "MIT", R.raw.mit,
        "https://github.com/python-hyper/hpack"),
    LicenseItem("hyperframe", "6.1.0", "MIT", R.raw.mit,
        "https://github.com/python-hyper/hyperframe"),
    LicenseItem("anyio", "4.16.0", "MIT", R.raw.mit,
        "https://github.com/agronholm/anyio"),
    LicenseItem("idna", "3.20", "BSD-3-Clause", R.raw.bsd3_clause,
        "https://github.com/kjd/idna"),
    LicenseItem("typing_extensions", "4.16.0", "PSF-2.0", R.raw.psf2,
        "https://github.com/python/typing_extensions"),
    LicenseItem("cryptography", "42.0.8", "Apache-2.0 OR BSD-3-Clause", R.raw.apache2,
        "https://github.com/pyca/cryptography"),
    LicenseItem("cffi", "2.1.1", "MIT-0", R.raw.mit0,
        "https://foss.heptapod.net/pypy/cffi"),
    LicenseItem("pycparser", "3.12", "BSD-3-Clause", R.raw.bsd3_clause,
        "https://github.com/eliben/pycparser"),
    LicenseItem("certifi", "MPL-2.0 bundle of CA certificates", "MPL-2.0", R.raw.mpl2,
        "https://github.com/certifi/python-certifi"),
    LicenseItem("Kotlin, kotlinx-coroutines", "2.2.20 / 1.9.0", "Apache-2.0", R.raw.apache2,
        "https://github.com/JetBrains/kotlin"),
    LicenseItem("AndroidX, Jetpack Compose", "core-ktx 1.15.0, BOM 2024.12.01",
        "Apache-2.0", R.raw.apache2, "https://developer.android.com/jetpack/androidx"),
    LicenseItem("Material Symbols", "Copyright (c) Google - contours of two icons",
        "Apache-2.0", R.raw.apache2, "https://fonts.google.com/icons"),
)

@Composable
fun AboutScreen(
    state: ProxyUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    // Раскрытый текст лицензии: приложение и компоненты — независимо, чтобы
    // длинный текст GPL не уезжал вместе со списком компонентов.
    var showAppLicense by rememberSaveable { mutableStateOf(false) }
    var expandedComponent by rememberSaveable { mutableStateOf(-1) }
    var openError by remember { mutableStateOf("") }

    val version = remember(context) { appVersion(context) }
    val dash = stringResource(R.string.value_none)

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
            Text(stringResource(R.string.about_title), fontSize = 20.sp,
                fontWeight = FontWeight.Bold, color = Palette.Text,
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
            AboutCard(stringResource(R.string.app_name)) {
                Text(stringResource(R.string.about_version, version),
                    fontSize = 13.sp, color = Palette.Text)
                // Версию ядра берём из состояния: Python сообщает её после
                // старта, до старта показываем прочерк.
                Text(stringResource(R.string.diag_core_version,
                    state.coreVersion.ifBlank { dash }),
                    fontSize = 13.sp, color = Palette.Muted)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.about_disclaimer),
                    fontSize = 12.sp, color = Palette.Muted)
            }

            AboutCard(stringResource(R.string.about_license_title)) {
                Text(stringResource(R.string.about_license_summary),
                    fontSize = 13.sp, color = Palette.Text)
                Spacer(Modifier.height(8.dp))
                TgwsButton(
                    text = stringResource(if (showAppLicense) {
                        R.string.about_hide_license
                    } else {
                        R.string.about_show_license
                    }),
                    color = Palette.Field,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showAppLicense = !showAppLicense },
                )
                if (showAppLicense) LicenseText(R.raw.gpl3)
            }

            AboutCard(stringResource(R.string.about_source_title)) {
                Text(stringResource(R.string.about_source_summary, SOURCE_URL),
                    fontSize = 13.sp, color = Palette.Text)
                Spacer(Modifier.height(8.dp))
                TgwsButton(
                    text = stringResource(R.string.about_open_repo),
                    color = Palette.Accent,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        openError = try {
                            uriHandler.openUri(SOURCE_URL)
                            ""
                        } catch (exc: Exception) {
                            // Браузера нет (или система не отдала нам ACTION_VIEW):
                            // адрес показан текстом выше, его можно скопировать.
                            Log.w(TAG, "не удалось открыть репозиторий", exc)
                            context.getString(R.string.about_no_browser)
                        }
                    },
                )
                if (openError.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(openError, fontSize = 12.sp, color = Palette.Bad)
                }
            }

            AboutCard(stringResource(R.string.about_third_party_title)) {
                Text(stringResource(R.string.about_third_party_summary),
                    fontSize = 12.sp, color = Palette.Muted)
                Spacer(Modifier.height(8.dp))
                // for по withIndex(), а НЕ forEachIndexed: лямбда у forEachIndexed
                // обычная, а ComponentRow — @Composable; из обычной лямбды
                // композабл вызывать нельзя («@Composable invocations can only
                // happen from the context of a @Composable function»). Цикл for
                // в теле композабла — можно.
                for ((index, item) in COMPONENTS.withIndex()) {
                    ComponentRow(
                        item = item,
                        expanded = expandedComponent == index,
                        onToggle = {
                            expandedComponent =
                                if (expandedComponent == index) -1 else index
                        },
                    )
                }
            }

            AboutCard(stringResource(R.string.about_icons_title)) {
                Text(stringResource(R.string.about_icons_summary),
                    fontSize = 12.sp, color = Palette.Muted)
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Карточка раздела: тот же стиль, что и секции настроек. */
@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .background(Palette.Card)
            .padding(12.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            color = Palette.Text)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** Строка списка компонентов: тап раскрывает полный текст лицензии. */
@Composable
private fun ComponentRow(item: LicenseItem, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(Dimens.RadiusButton))
            .background(Palette.Field)
            .clickable(onClick = onToggle)
            .padding(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = Palette.Text, modifier = Modifier.weight(1f))
            Text(item.license, fontSize = 11.sp, color = Palette.Accent)
        }
        if (item.details.isNotEmpty()) {
            Text(item.details, fontSize = 11.sp, color = Palette.Muted)
        }
        if (item.url.isNotEmpty()) {
            Text(item.url, fontSize = 11.sp, color = Palette.Muted)
        }
        Text(
            text = stringResource(if (expanded) {
                R.string.about_hide_license
            } else {
                R.string.about_show_license
            }),
            fontSize = 11.sp,
            color = Palette.Accent,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (expanded && item.rawRes != 0) LicenseText(item.rawRes)
    }
}

/** Полный текст лицензии из `res/raw` — моноширинный, в прокручиваемом блоке. */
@Composable
private fun LicenseText(rawRes: Int) {
    val context = LocalContext.current
    // Читаем один раз на ресурс: повторные раскрытия не ходят в файл заново.
    val text = remember(rawRes) {
        context.resources.openRawResource(rawRes).bufferedReader().use {
            it.readText()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(Dimens.RadiusButton))
            .background(Palette.Bg)
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = Palette.Muted)
    }
}

/** Версия приложения для экрана «О приложении»: «1.11.2 (223)». */
private fun appVersion(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName ?: "?"} (${PackageInfoCompat.getLongVersionCode(info)})"
} catch (exc: Exception) {
    Log.w(TAG, "не удалось прочитать версию приложения", exc)
    "?"
}
