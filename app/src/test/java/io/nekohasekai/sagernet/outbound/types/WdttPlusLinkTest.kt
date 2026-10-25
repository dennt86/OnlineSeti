package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.`import`.ProfileImport
import org.junit.Assert.*
import org.junit.Test

class WdttPlusLinkTest {
    private val hash = "abcDEF123_-xyz789"

    @Test fun connectLinksBecomeWdttPlusProfiles() {
        val result = ProfileImport.parse("wdtt://connect?v=1&host=192.0.2.5&dtls=56000&wg=56001&local=9000&password=p%40ss&hashes=$hash&name=Home&max_workers=36")
        val profile = result.outbounds.single() as WdttPlus
        assertEquals("WDTT Plus", profile.displayType())
        assertEquals("192.0.2.5", profile.server)
        assertEquals(56000, profile.serverPort)
        assertEquals("p@ss", profile.password)
        assertEquals(listOf(hash), profile.hashes)
        assertEquals("Home", profile.name)
        assertEquals(36, profile.workers)
        val again = WdttPlus().apply { assertTrue(parseFromLink(profile.exportToLink())) }
        // The device id is per install and never travels in a link.
        assertEquals(profile.exportToJson().also { it.remove("device_id") }.toCompact(), again.exportToJson().also { it.remove("device_id") }.toCompact())
    }

    @Test fun plainWdttLinksAreShownAsWdtt() {
        val profile = ProfileImport.parse("wdtt://192.0.2.6:56000:56001:9000:secret:$hash").outbounds.single() as WdttPlus
        assertEquals("WDTT", profile.displayType())
        assertEquals(56001, profile.wgPort)
        assertEquals("wdtt://192.0.2.6:56000:56001:9000:secret:$hash", profile.exportToLink())
    }

    @Test fun startsLikeTheOriginalApp() {
        val profile = WdttPlus()
        assertTrue(profile.parseFromLink("wdtt://connect?v=1&host=192.0.2.5&dtls=56000&wg=56001&local=9000&password=p&hashes=$hash"))
        val config = profile.privateConfig(1080, 1081, app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials("u", "pw"))
        val args = config.array("args").strings()
        for (flag in listOf("-startup-config-stdin=true", "-socks-auth=true", "-vkcalls-preflight=true", "-config-first-start=true"))
            assertTrue(flag, flag in args)
        assertEquals("18", args[args.indexOf("-n") + 1])
        assertEquals("socks5", args[args.indexOf("-mode") + 1])
        assertEquals("pw", config.obj("start").string("socks_password"))
    }
}
