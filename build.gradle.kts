// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
// Корневой build: версии плагинов задаются здесь и переиспользуются в :app.
// Если Android Studio предложит обновить версии — соглашайтесь: значения ниже
// взяты по состоянию на конец 2025 (AGP 8.13, Kotlin 2.2, Chaquopy 17).
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("com.chaquo.python") version "17.0.0" apply false
}
