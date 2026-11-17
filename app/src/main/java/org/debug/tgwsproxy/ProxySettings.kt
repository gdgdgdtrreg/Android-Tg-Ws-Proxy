// SPDX-FileCopyrightText: 2026 gdgdgdtrreg
// SPDX-License-Identifier: GPL-3.0-or-later
package org.debug.tgwsproxy

import org.json.JSONObject

/**
 * Настройки прокси. Ключи JSON — те же, что были в Kivy-версии
 * (`proxy_settings.json`), поэтому Python-мост не пришлось менять.
 */
data class ProxySettings(
    val ip: String = "127.0.0.1",
    val allowLan: Boolean = false,
    val port: Int = 1443,
    val secret: String = "",
    val dcs: String = "2:149.154.167.220\n4:149.154.167.220",
    val forceTestDc: Boolean = false,
    val cfProxyEnable: Boolean = true,
    val cfProxyDomain: String = "",
    val cfWorkerDomain: String = "",
    val h2Media: Boolean = true,
    val noSecure: Boolean = false,
    val fakeTlsDomain: String = "",
    val buffer: Int = 256,
    val pool: Int = 4,
    val verbose: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("ip", ip)
        put("allow_lan", allowLan)
        put("port", port)
        put("secret", secret)
        put("dcs", dcs)
        put("force_test_dc", forceTestDc)
        put("cf_proxy_enable", cfProxyEnable)
        put("cf_proxy_domain", cfProxyDomain)
        put("cf_worker_domain", cfWorkerDomain)
        put("h2_media", h2Media)
        put("no_secure", noSecure)
        put("fake_tls_domain", fakeTlsDomain)
        put("buffer", buffer)
        put("pool", pool)
        put("verbose", verbose)
        // Запись лога в файл больше не выключается: переключатель убран из
        // настроек, мост всегда получает log_to_file=true. Место ограничено
        // ротацией (1 МБ + копия, см. proxy_runner.LOG_MAX_BYTES), а лог
        // перестаёт требовать от пользователя «включить заранее».
        put("log_to_file", true)
    }.toString()

    companion object {
        fun fromJson(raw: String?): ProxySettings {
            if (raw.isNullOrBlank()) return ProxySettings()
            return try {
                val json = JSONObject(raw)
                ProxySettings(
                    ip = json.optString("ip", "127.0.0.1"),
                    allowLan = json.optBoolean("allow_lan", false),
                    port = json.optInt("port", 1443),
                    secret = json.optString("secret", ""),
                    dcs = json.optString("dcs", "2:149.154.167.220\n4:149.154.167.220"),
                    forceTestDc = json.optBoolean("force_test_dc", false),
                    cfProxyEnable = json.optBoolean("cf_proxy_enable", true),
                    cfProxyDomain = json.optString("cf_proxy_domain", ""),
                    cfWorkerDomain = json.optString("cf_worker_domain", ""),
                    h2Media = json.optBoolean("h2_media", true),
                    noSecure = json.optBoolean("no_secure", false),
                    fakeTlsDomain = json.optString("fake_tls_domain", ""),
                    buffer = json.optInt("buffer", 256),
                    pool = json.optInt("pool", 4),
                    verbose = json.optBoolean("verbose", false),
                )
            } catch (_: Exception) {
                ProxySettings()
            }
        }

        private val HEX = "0123456789abcdef".toSet()

        fun isSecretValid(secret: String): Boolean =
            secret.length == 32 && secret.all { it in HEX }

        /**
         * Проверка перед стартом: null — настройки годятся, иначе ключ сообщения.
         *
         * Возвращаем [MessageKey], а не готовый текст: строки интерфейса живут в
         * `res/values/strings.xml` (и `values-en/`), а здесь Context нет.
         * Перевод подставляет вызывающий код — [localize].
         */
        fun validate(settings: ProxySettings): MessageKey? = when {
            settings.port !in 1..65535 -> MessageKey("error_port_range")
            settings.secret.isNotEmpty() && !isSecretValid(settings.secret) ->
                MessageKey("error_secret_hex")
            else -> badDcLine(settings.dcs)
                ?.let { MessageKey("error_dc_line", listOf(it)) }
        }

        /** Первая строка списка DC, которая не похожа на «2:149.154.167.220». */
        private fun badDcLine(dcs: String): String? {
            for (line in dcs.split('\n')) {
                val text = line.trim()
                if (text.isEmpty() || text.startsWith("#")) continue
                val (dc, ip) = text.split(':', limit = 2)
                    .let { if (it.size == 2) it[0] to it[1] else "" to "" }
                val dcNumber = dc.toIntOrNull()
                if (dcNumber == null || ip.isBlank() || !ip.matches(IPV4)) return text
            }
            return null
        }

        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        /** 32 hex-символа, если секрет ещё не задан (как кнопка «Gen»). */
        fun generateSecret(): String {
            val chars = "0123456789abcdef"
            return (1..32).map { chars.random() }.joinToString("")
        }
    }
}
