package app.qthrone.mobile.outbound.config

import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.types.Socks
import org.junit.Assert.*
import org.junit.Test

class AndroidVpnListenerTest {
    private fun build(vpn: Boolean): JsonObject {
        val profile = Socks().apply { server = "192.0.2.1"; serverPort = 1080 }
        val result = ConfigGenerator(ProfileProvider { profile }, GeneratorSettings(
            vpnMode = vpn, mixedInboundEnabled = true, httpProxyEnabled = true,
        )).build(1)
        assertNull(result.error)
        return JsonInput.parseObject(result.coreConfig)
    }

    @Test fun vpnDoesNotExposeProxyOrDnsListenersEvenWithLegacySettings() {
        val types = build(true).array("inbounds").map { (it as JsonObject).string("type") }
        assertEquals(listOf("tun"), types)
    }

    @Test fun explicitProxyModeStillHasItsProxyListener() {
        val types = build(false).array("inbounds").map { (it as JsonObject).string("type") }
        assertTrue(types.contains("socks") && types.contains("http"))
        assertFalse(types.contains("tun"))
    }

    @Test fun perAppBypassIsEmittedAsTunExclusionAndUidGuard() {
        val profile = Socks().apply { server = "192.0.2.1"; serverPort = 1080 }
        val result = ConfigGenerator(ProfileProvider { profile }, GeneratorSettings(
            vpnMode = true,
            perAppEnabled = true,
            perAppBypass = true,
            perAppPackages = listOf("com.example.bank"),
            perAppAllowedUids = listOf(10001, 10002),
        )).build(1)
        assertNull(result.error)
        val config = JsonInput.parseObject(result.coreConfig)
        val tun = config.array("inbounds").filterIsInstance<JsonObject>()
            .single { it.string("type") == "tun" }
        assertEquals(listOf("com.example.bank"), tun.array("exclude_package").map { it.toString().trim('"') })
        assertNull(tun["include_package"])
        assertEquals(listOf(10001, 10002), VpnAppGuard.allowedUids(
            mapOf(10001 to listOf("com.example.other"), 10002 to listOf("com.example.another")),
            setOf("com.example.bank"), bypass = true,
        ))
    }
}
