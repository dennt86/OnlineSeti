package app.qthrone.mobile.bg.carrier

import java.net.URI
import java.net.URLDecoder

/** Redirects stay on VK; tokens and cookies never reach an external redirect target. */
internal object VkRelayOAuth {
    fun trustedHttps(uri: URI): Boolean {
        val host = uri.host.orEmpty().lowercase()
        return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            (host == "vk.com" || host.endsWith(".vk.com") || host == "vk.ru" || host.endsWith(".vk.ru"))
    }
    fun tokenFields(url: String,state: String): Pair<String,Long>? = runCatching {
        val uri = URI(url)
        if(!trustedHttps(uri) || uri.host !in listOf("oauth.vk.com","oauth.vk.ru") || uri.path != "/blank.html") return null
        val fields = uri.rawFragment.orEmpty().split('&').associate { field ->
            URLDecoder.decode(field.substringBefore('='),"UTF-8") to URLDecoder.decode(field.substringAfter('=',""),"UTF-8")
        }
        val token = fields["access_token"].orEmpty()
        if(fields["state"] != state || token.isBlank()) return null
        token to (fields["expires_in"]?.toLongOrNull() ?: 0L)
    }.getOrNull()
    fun nextAuthorizeUrl(body: String): String? {
        val href = Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
        if(href != null) return href.replace("&amp;","&")
        return Regex("""(https://login\.vk\.com/\?act=grant_access[^"'\s<]+)""")
            .find(body)?.groupValues?.get(1)?.replace("&amp;","&")
    }
}
