package app.qthrone.mobile.deploy

import app.qthrone.mobile.outbound.link.Hosts
import app.qthrone.mobile.outbound.link.LinkBuilder
import org.json.JSONObject

/** Importable qWDTT admin links for deployed servers. */
object DeployConfig {
    /** [ownDeviceId] is this phone's identity; older servers can carry their deployed owner's ID. */
    fun link(config: JSONObject, ownDeviceId: String? = null): String {
        require(config.getString("type") == "qwdtt") { "Тип конфигурации больше не поддерживается" }
        val url = LinkBuilder("qwdtt").apply { host = "config"; plusAsEscape = true }
        url.addQueryItem("name", config.optString("name").ifBlank { config.getString("password") })
        url.addQueryItem("peer", Hosts.displayAddress(config.getString("server"), config.getInt("server_port")))
        url.addQueryItem("hashes", config.optJSONArray("hashes")?.let { a ->
            (0 until a.length()).joinToString(",") { a.getString(it) }
        }.orEmpty())
        url.addQueryItem("workers", "18")
        url.addQueryItem("port", "9000")
        url.addQueryItem("pass", config.getString("password"))
        config.optString("device_id").takeIf { it.isNotBlank() && it != ownDeviceId }?.let {
            url.addQueryItem("device_id", it)
        }
        return url.build()
    }

    fun display(saved: String): String = if (saved.trimStart().startsWith("{"))
        runCatching { link(JSONObject(saved)) }.getOrDefault(saved) else saved
}
