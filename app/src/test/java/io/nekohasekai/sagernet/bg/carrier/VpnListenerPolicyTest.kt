package app.qthrone.mobile.bg.carrier
import org.junit.Assert.*
import org.junit.Test
class VpnListenerPolicyTest {
    @Test fun tunOnlyAllowed() { VpnListenerPolicy.check("""{"inbounds":[{"type":"tun"}]}""") }
    @Test fun unauthenticatedCustomMixedIsRejected() {
        try { VpnListenerPolicy.check("""{"inbounds":[{"type":"mixed","listen":"127.0.0.1"}]}""");fail("unauthenticated proxy allowed") } catch(_:IllegalStateException) {}
    }
    @Test fun authenticatedLoopbackAccepted() {
        VpnListenerPolicy.check("""{"inbounds":[{"type":"socks","listen":"127.0.0.1","users":[{"username":"bridge","password":"01234567890123456789012345678901"}]}]}""")
    }
    @Test fun dashboardWithoutSecretRejected() {
        try { VpnListenerPolicy.check("""{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090"}}}""");fail("API without secret allowed") } catch(_:IllegalStateException) {}
    }
}
