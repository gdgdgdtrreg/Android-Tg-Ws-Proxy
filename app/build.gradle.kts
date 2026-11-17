// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// ---------------------------------------------------------------------------
// Python для сборки (buildPython)
//
// Chaquopy для сборки запускает pip на ВАШЕМ компьютере, поэтому версия
// локального Python обязана совпадать с версией Python в приложении — иначе
// сборка падает с «Couldn't find Python 3.11».
//
// Ниже версия определяется автоматически: ищем установленные Python через
// `py -0p` (Windows launcher) и `python --version`. Переопределить можно в
// gradle.properties строкой pythonVersion=3.13.
// ---------------------------------------------------------------------------

/** Запускает команду и возвращает её вывод, либо null, если не получилось. */
fun runProcess(vararg command: String): String? = try {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
} catch (_: Exception) {
    null
}

/** Все Python, известные Windows-launcher'у: версия -> путь к python.exe. */
fun detectLocalPythons(): Map<String, String> {
    val result = linkedMapOf<String, String>()
    runProcess("py", "-0p")?.lineSequence()?.forEach { line ->
        // строка вида " -V:3.13 *        C:\...\Python313\python.exe"
        val match = Regex("""-V:(\d+\.\d+)""").find(line) ?: return@forEach
        val path = Regex("""([A-Za-z]:\\[^"]*python\.exe)""").find(line)?.groupValues?.get(1)
        if (path != null) result[match.groupValues[1]] = path
    }
    if (result.isEmpty()) {
        // Unix-подобные системы и Windows без launcher'а
        for (minor in SUPPORTED_PYTHONS) {
            val output = runProcess("python$minor", "--version") ?: continue
            val version = Regex("""(\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1)
                ?: continue
            result[version.substringBeforeLast('.')] = "python$minor"
        }
        runProcess("python", "--version")?.let { output ->
            Regex("""(\d+\.\d+)\.\d+""").find(output)?.let { match ->
                result.putIfAbsent(match.groupValues[1], "python")
            }
        }
    }
    return result
}

/**
 * Версии для автоподбора — от новых к старым.
 *
 * Chaquopy 17 умеет и Python 3.14, но в его Android-репозитории нет wheels
 * cryptography под cp314, а без `cryptography` ядро не зашифрует поток
 * (это единственная нативная зависимость). Поэтому автоподбор идёт до 3.10.
 */
val SUPPORTED_PYTHONS = listOf("3.13", "3.12", "3.11", "3.10")

/**
 * Самая новая версия Python, для которой Chaquopy собирает 32-битный рантайм.
 *
 * Начиная с Python 3.12 32-битные ABI (`armeabi-v7a`, `x86`) из Chaquopy убраны
 * (chaquo/chaquopy#709), а `PythonPlugin.getAbis()` падает с «Python 3.13 is not
 * available for the ABI 'armeabi-v7a'». Поэтому сборка под 32-битные телефоны
 * означает и переход на Python 3.11.
 */
val PYTHON_32BIT = "3.11"

/** Номер версии сборки; у 32-битного варианта он на 1 меньше (см. productFlavors). */
val BASE_VERSION_CODE = 223

/** Свойство Gradle как флаг: «true», «1», «yes», «on» в любом регистре — да. */
fun boolProperty(name: String): Boolean =
    when ((findProperty(name) as Any?)?.toString()?.trim()?.lowercase()) {
        "true", "1", "yes", "on" -> true
        else -> false
    }

/** Младшая версия числом: «3.11» -> 11. */
fun minorOf(version: String): Int = version.substringAfter('.').toIntOrNull() ?: 13

// ---------------------------------------------------------------------------
// 32-битные телефоны (armeabi-v7a)
//
// abi32=true в gradle.properties или -Pabi32=true в командной строке добавляет
// второй продуктовый вариант arm32: одна сборка даёт два APK, по одному на ABI,
// и в каждом только свой рантайм Python. Варианты (а не ABI-splits) — потому что
// Chaquopy кладёт нативные компоненты ещё и в assets, а splits их не разделяют:
// «раздельные» APK вышли бы почти такими же толстыми (FAQ Chaquopy «How can I
// make my app smaller?» прямо рекомендует flavor dimension).
// ---------------------------------------------------------------------------
val want32 = boolProperty("abi32")
val wantEmulator = boolProperty("emulator")

val localPythons = try {
    detectLocalPythons()
} catch (_: Exception) {
    emptyMap()
}

/** Что просили: сначала gradle.properties, затем найденный Python, затем 3.13. */
val requestedPython: String = (findProperty("pythonVersion") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: SUPPORTED_PYTHONS.firstOrNull { it in localPythons.keys }
    ?: "3.13"

/**
 * Итоговая версия Python в приложении.
 *
 * Для 32-битного ABI она обязана быть 3.11 или старше, поэтому при abi32=true
 * версия понижается автоматически — сначала до той, что уже установлена на
 * компьютере, иначе до [PYTHON_32BIT].
 */
val pythonVersion: String = if (!want32 || minorOf(requestedPython) <= 11) {
    requestedPython
} else {
    val downgraded = SUPPORTED_PYTHONS
        .firstOrNull { minorOf(it) <= 11 && it in localPythons.keys }
        ?: PYTHON_32BIT
    logger.lifecycle(
        "TG-WS-Proxy: abi32=true — 32-битный armeabi-v7a у Chaquopy есть только " +
            "для Python 3.11 и старше, поэтому вместо $requestedPython берём $downgraded"
    )
    downgraded
}

val buildPythonPath: String? = localPythons[pythonVersion]
    ?.takeIf { it.endsWith(".exe") && File(it).exists() }

val pythonMinor = minorOf(pythonVersion)

// 32-битные ABI (armeabi-v7a, x86) Chaquopy собирает только для Python 3.11 и
// старше; начиная с 3.12 доступны лишь arm64-v8a и x86_64.
val emulatorAbis = if (wantEmulator) listOf("x86_64") else emptyList()
val abis: List<String> = if (want32) {
    // Раскладываются по продуктовым вариантам (см. android.productFlavors).
    listOf("arm64-v8a", "armeabi-v7a")
} else {
    listOf("arm64-v8a") + emulatorAbis
}

val abiNote = if (want32) {
    "два варианта: app-arm64-*.apk и app-arm32-*.apk"
} else {
    "один вариант; для 32-битных телефонов включите abi32=true"
}
val pythonNote = if (localPythons.isNotEmpty()) {
    "найдены локально: ${localPythons.keys.joinToString(", ")}"
} else {
    "локальные Python не найдены: поставьте Python $pythonVersion или задайте pythonVersion"
}

logger.lifecycle(
    "TG-WS-Proxy: Python приложения $pythonVersion" +
        (buildPythonPath?.let { ", для сборки: $it" } ?: ", для сборки: из PATH") +
        ", ABI: ${abis.joinToString(", ")} — $abiNote ($pythonNote)"
)
if (want32 && pythonVersion !in localPythons.keys) {
    logger.warn(
        "TG-WS-Proxy: для 32-битной сборки на этом компьютере нужен Python " +
            "$pythonVersion — Chaquopy запускает локальный pip той же версии, а он " +
            "не найден. Поставьте Python $pythonVersion (python.org/downloads) или " +
            "уберите abi32=true из gradle.properties."
    )
}
if (pythonMinor >= 14) {
    logger.warn(
        "TG-WS-Proxy: для Python $pythonVersion в репозитории Chaquopy нет wheels " +
            "cryptography (есть до 3.13) — сборка упадёт на pip install. " +
            "Поставьте Python 3.13 и укажите pythonVersion=3.13."
    )
}

android {
    namespace = "org.debug.tgwsproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.tgwsproxy.android"
        minSdk = 24
        targetSdk = 35
        versionCode = BASE_VERSION_CODE
        versionName = "1.11.2"

        if (!want32) {
            ndk {
                // Один вариант сборки: ABI перечисляем здесь — Chaquopy требует
                // непустой ndk.abiFilters. Для эмулятора: -Pemulator=true
                abiFilters += abis
            }
        }
        // При abi32=true ABI заданы в продуктовых вариантах ниже. Дублировать их
        // здесь нельзя: Chaquopy складывает списки defaultConfig и варианта
        // (PythonPlugin.getAbis), и в 32-битный APK попал бы 64-битный рантайм.
    }

    if (want32) {
        flavorDimensions += "abi"
        productFlavors {
            create("arm64") {
                dimension = "abi"
                ndk { abiFilters += listOf("arm64-v8a") + emulatorAbis }
            }
            create("arm32") {
                dimension = "abi"
                // versionCode на единицу меньше, чем у 64-битного варианта: при
                // загрузке двух APK в Google Play более высокий versionCode должен
                // быть у приоритетного ABI — тогда 64-битные устройства получают
                // arm64, а 32-битные (которые arm64 не поддерживают) — arm32.
                versionCode = BASE_VERSION_CODE - 1
                ndk { abiFilters += listOf("armeabi-v7a") }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Версия байткода Kotlin: должна совпадать с compileOptions выше, иначе
// сборка ругается на несовместимые JVM-таргеты.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Python-ядро: вендоренная копия Flowseal/tg-ws-proxy (папка proxy/) плюс
// тонкий мост proxy_runner.py. Обновление ядра — копирование папки proxy/.
chaquopy {
    defaultConfig {
        version = pythonVersion
        buildPythonPath?.let { path -> buildPython(path) }
        pip {
            // httpx и его зависимости — чистый Python (wheels есть на PyPI).
            install("httpx[http2]==0.28.1")
            // cryptography — единственный пакет с нативным кодом. Chaquopy 17
            // ставит пакеты ТОЛЬКО готовыми wheels, а для Android они собраны
            // в репозитории Chaquopy; 42.0.8 — самая свежая версия с wheel'ами
            // для Python 3.10–3.13 / arm64-v8a (проверено в индексе
            // chaquo.com/pypi-13.1). Более новые версии на PyPI собраны под
            // другие платформы — без пина сборка упала бы.
            install("cryptography==42.0.8")
            install("certifi")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    testImplementation("junit:junit:4.13.2")
}
