package app.qthrone.mobile.ui

import java.net.URI

/** Only our HTTPS subscriptions may enter through the branded quick setup link. */
object OnlinesetiLinks {
    const val BOT = "https://t.me/onlineseti_bot"
    private val hosts = setOf("openseti.ru", "onlineseti.ru")

    fun subscription(link: String): String? = runCatching {
        val outer = URI(link)
        if (outer.scheme != "onlineseti" || outer.host != "import" ||
            outer.rawUserInfo != null || outer.port != -1 || outer.rawFragment != null ||
            !outer.rawPath.isNullOrEmpty()) return null
        val params = outer.rawQuery?.split('&') ?: return null
        if (params.size != 1 || !params[0].startsWith("url=")) return null
        val value = java.net.URLDecoder.decode(params[0].substring(4), "UTF-8")
        val uri = URI(value)
        if (uri.scheme != "https" || uri.host !in hosts || uri.port != -1 ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            !Regex("/subscription/[A-Za-z0-9_-]{8,128}").matches(uri.rawPath ?: "")) return null
        value
    }.getOrNull()
}
