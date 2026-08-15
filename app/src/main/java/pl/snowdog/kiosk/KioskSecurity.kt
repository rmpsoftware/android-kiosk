package pl.snowdog.kiosk

import java.net.URI

object KioskSecurity {
    private val jobIdPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    private val hostPattern = Regex("^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|(?:\\d{1,3}\\.){3}\\d{1,3})$")

    fun normalizeHttpsOrigin(value: String?): String? {
        val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (uri.path != null && uri.path != "" && uri.path != "/") return null
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        return "https://${uri.host.lowercase()}$port"
    }

    fun originOf(value: String?): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return null
        val defaultPort = (uri.scheme.equals("https", true) && uri.port == 443) ||
            (uri.scheme.equals("http", true) && uri.port == 80)
        val port = if (uri.port == -1 || defaultPort) "" else ":${uri.port}"
        return "${uri.scheme.lowercase()}://${uri.host.lowercase()}$port"
    }

    fun isAllowedUrl(url: String?, allowedOrigin: String): Boolean =
        originOf(url) == normalizeHttpsOrigin(allowedOrigin)

    fun isValidJobId(value: String): Boolean = jobIdPattern.matches(value)

    fun isValidPrinterHost(value: String): Boolean {
        if (!hostPattern.matches(value) || value.contains("..")) return false
        val octets = value.split('.')
        return octets.size != 4 || octets.all { it.toIntOrNull() in 0..255 }
    }

    fun isValidPrinterPort(value: Int): Boolean = value in 1..65535
}
