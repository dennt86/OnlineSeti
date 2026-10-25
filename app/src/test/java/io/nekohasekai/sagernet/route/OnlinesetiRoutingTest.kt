package app.qthrone.mobile.route

import org.junit.Assert.*
import org.junit.Test
import app.qthrone.mobile.outbound.config.*
import app.qthrone.mobile.outbound.types.Socks
import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.json.JsonObject

class OnlinesetiRoutingTest {
    @Test fun generatedConfigHasDirectDnsAndOfflineSeeds() {
        val proxy = Socks().apply { server = "192.0.2.1"; serverPort = 1080 }
        val catalog = RuleSetCatalog(OnlinesetiRouting.bundledTags.map { it to "https://example.org/$it.srs" })
        val generated = ConfigGenerator(ProfileProvider { proxy }, GeneratorSettings(vpnMode = true),
            routing = RoutingInput(OnlinesetiRouting.defaultProfile(), catalog)).build(1)
        assertNull(generated.error)
        val config = JsonInput.parseObject(generated.coreConfig)
        val route = config.obj("route")
        assertEquals("proxy", route.string("final"))
        val sets = route.array("rule_set").filterIsInstance<JsonObject>()
        assertEquals(4, sets.size)
        sets.forEach {
            assertEquals(OnlinesetiRouting.seedPath(it.string("tag")), it.string("initial_path"))
            assertEquals("proxy", it.obj("http_client").string("detour"))
        }
        assertTrue(route.array("rules").filterIsInstance<JsonObject>().any {
            it.array("rule_set").contains("geoip-ru") && it.string("outbound") == "direct"
        })
        assertTrue(config.obj("dns").array("rules").filterIsInstance<JsonObject>().any {
            it.array("rule_set").contains("geosite-category-ru") && it.string("server") == "dns-direct"
        })
    }
    @Test fun russianAndLocalTrafficIsDirectWhileDefaultRemainsProxy() {
        val p = OnlinesetiRouting.defaultProfile()
        assertEquals(OutboundIds.PROXY, p.default_outbound_id)
        assertEquals("hijack-dns", p.rules.first().action)
        assertTrue(p.rules[1].ip_is_private)
        assertEquals(OutboundIds.DIRECT, p.rules[1].outbound_id)
        assertEquals(OutboundIds.DIRECT, p.rules[2].outbound_id)
        assertTrue(p.directSites().contains("ruleset:geosite-category-ru"))
        assertTrue(p.directIps().contains("ruleset:geoip-ru"))
    }

    @Test fun migrationPreservesCustomizedProfiles() {
        assertTrue(OnlinesetiRouting.isLegacyDefault(RouteProfile.defaultProfile()))
        assertFalse(OnlinesetiRouting.isLegacyDefault(RouteProfile.defaultProfile().apply {
            rules[0].domain.add("example.org")
        }))
        assertFalse(OnlinesetiRouting.isLegacyDefault(RouteProfile.defaultProfile().apply {
            rules.add(RouteRule().apply { ip_is_private = true })
        }))
        assertFalse(OnlinesetiRouting.isLegacyDefault(RouteProfile.defaultProfile().apply {
            default_outbound_id = OutboundIds.DIRECT
        }))
        assertFalse(OnlinesetiRouting.isLegacyDefault(OnlinesetiRouting.defaultProfile()))
    }

    @Test fun onlyBundledSetsUseOfflineSeed() {
        assertNull(OnlinesetiRouting.seedPath("geosite-unknown"))
        assertEquals("onlineseti-rules/geoip-ru.srs", OnlinesetiRouting.seedPath("geoip-ru"))
    }
}
