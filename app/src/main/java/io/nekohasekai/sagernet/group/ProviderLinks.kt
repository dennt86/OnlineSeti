package app.qthrone.mobile.group

import java.net.URI
import java.net.URLDecoder

object ProviderLinks {
    data class Link(val provider: String, val data: String)
    fun decode(text: String): Link? {
        val match = Regex("^(happ|incy)://(?:add|import)/(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).matchEntire(text.trim()) ?: return null
        var data = match.groupValues[2]
        if (!data.contains("://")) data = URLDecoder.decode(data.replace("+", "%2B"), "UTF-8")
        return Link(if (match.groupValues[1].equals("incy", true)) "Incy" else "Happ", data)
    }
    fun subscription(text: String): Link? = decode(text)?.takeIf {
        runCatching { URI(it.data).scheme.lowercase() in listOf("https", "http") }.getOrDefault(false)
    }
}
