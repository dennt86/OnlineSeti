package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.utils.WdttPlusNetworkPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WdttPlusNetworkPolicyTest {
    @Test
    fun onlyCellularDefaultIsAllowed() {
        assertTrue(WdttPlusNetworkPolicy.isCellularDefault(hasCellular = true, hasWifi = false))
        assertFalse(WdttPlusNetworkPolicy.isCellularDefault(hasCellular = false, hasWifi = true))
        assertFalse(WdttPlusNetworkPolicy.isCellularDefault(hasCellular = false, hasWifi = false))
        assertFalse(WdttPlusNetworkPolicy.isCellularDefault(hasCellular = true, hasWifi = true))
    }

    @Test
    fun wifiFallbackPrefersAutomaticSelectorOverIndividualServers() {
        val fallback = WdttPlusNetworkPolicy.wifiFallback(
            selectedId = 1,
            candidates = listOf(
                WdttPlusNetworkPolicy.FallbackCandidate(1, "wdttplus", true, 10),
                WdttPlusNetworkPolicy.FallbackCandidate(2, "hysteria", true, 120),
                WdttPlusNetworkPolicy.FallbackCandidate(3, "tuic", true, 80),
                WdttPlusNetworkPolicy.FallbackCandidate(4, "vless", false, 20),
                WdttPlusNetworkPolicy.FallbackCandidate(5, "autoselector", true, 5),
            ),
        )
        assertTrue(fallback?.id == 5L)
    }

    @Test
    fun wifiFallbackIsAbsentWhenGroupHasNoUsableAlternative() {
        assertTrue(WdttPlusNetworkPolicy.wifiFallback(
            selectedId = 1,
            candidates = listOf(WdttPlusNetworkPolicy.FallbackCandidate(1, "wdttplus", true, 10)),
        ) == null)
    }

    @Test
    fun wifiFallbackUsesFastestNormalProfileWhenNoAutomaticSelectorExists() {
        val fallback = WdttPlusNetworkPolicy.wifiFallback(
            selectedId = 1,
            candidates = listOf(
                WdttPlusNetworkPolicy.FallbackCandidate(1, "wdttplus", true, 10),
                WdttPlusNetworkPolicy.FallbackCandidate(2, "hysteria", true, 120),
                WdttPlusNetworkPolicy.FallbackCandidate(3, "tuic", true, 80),
            ),
        )
        assertTrue(fallback?.id == 3L)
    }
}
