package app.qthrone.mobile.outbound.import

import org.junit.Assert.assertEquals
import org.junit.Test
import org.yaml.snakeyaml.error.YAMLException

class ClashYamlSecurityTest {
    @Test fun ordinarySubscriptionStillParses() {
        assertEquals(1, ClashYaml.proxies("proxies:\n  - name: test\n    type: socks5\n    server: localhost\n    port: 1080\n")!!.size)
    }

    @Test(expected = YAMLException::class) fun deeplyNestedInputIsRejected() {
        ClashYaml.proxies("proxies: " + "[".repeat(1000) + "0" + "]".repeat(1000))
    }

    @Test(expected = YAMLException::class) fun aliasExpansionIsRejected() {
        ClashYaml.proxies("a: &a [1, 2]\nproxies: [" + List(100) { "*a" }.joinToString(",") + "]")
    }

    @Test(expected = YAMLException::class) fun objectConstructionIsRejected() {
        ClashYaml.proxies("!!java.util.Date {}")
    }

    @Test(expected = YAMLException::class) fun oversizedYamlIsRejected() {
        ClashYaml.proxies("proxies: []\npadding: " + "x".repeat(4 * 1024 * 1024))
    }
}
