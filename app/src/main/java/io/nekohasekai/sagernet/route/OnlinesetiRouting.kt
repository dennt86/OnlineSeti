package app.qthrone.mobile.route

/** Same lists as the upstream Bypass_Russia profile, shipped with the APK for offline startup. */
object OnlinesetiRouting {
    val bundledTags = listOf("geoip-ru", "geosite-ru", "geosite-category-ru", "geosite-ru-available-only-inside")

    fun seedPath(tag: String): String? =
        if (tag in bundledTags) "onlineseti-rules/$tag.srs" else null

    fun defaultProfile(): RouteProfile = RouteProfile.defaultProfile().apply {
        name = "Onlineseti · Россия напрямую"
        rules.add(RouteRule().apply {
            name = "Локальная сеть напрямую"
            ip_is_private = true
        })
        rules.add(RouteRule().apply {
            name = "Российские сервисы напрямую по Wi-Fi"
            rule_set.addAll(bundledTags)
            // On mobile networks, Russian destinations can be unreachable directly. Keep the existing
            // bypass behavior on Wi-Fi, while letting cellular traffic use the configured proxy.
            network_type.add("wifi")
        })
    }

    /** Upgrade only the stock profile from before cellular-aware bypass; preserve user-edited routing. */
    fun isUnrestrictedRussiaDefault(p: RouteProfile): Boolean {
        if (p.name != "Onlineseti · Россия напрямую" || p.is_remote || p.is_raw || p.auto_update ||
            p.prevent_modifications || p.remote_url.isNotEmpty() || p.raw_route.isNotEmpty() ||
            p.default_outbound_id != OutboundIds.PROXY || p.endpoint_profile_ids != "[]" ||
            p.inner_hop_endpoint_ids != "[]" || p.rules.size != 3) return false
        val expected = defaultProfile().apply {
            rules.last().network_type.clear()
            rules.last().name = "Российские сервисы напрямую"
        }.rules
        return p.rules.indices.all { index ->
            RouteRule::class.java.fields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .all { it.get(p.rules[index]) == it.get(expected[index]) }
        }
    }

    /** Only migrate the untouched 0.1.0 profile; never erase custom rules or endpoint selections. */
    fun isLegacyDefault(p: RouteProfile): Boolean {
        if (p.name != "Default" || p.is_remote || p.is_raw || p.auto_update || p.prevent_modifications ||
            p.remote_url.isNotEmpty() || p.raw_route.isNotEmpty() || p.default_outbound_id != OutboundIds.PROXY ||
            p.endpoint_profile_ids != "[]" || p.inner_hop_endpoint_ids != "[]" || p.rules.size != 1) return false
        val expected = RouteProfile.defaultProfile().rules.single()
        return RouteRule::class.java.fields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .all { it.get(p.rules.single()) == it.get(expected) }
    }
}
