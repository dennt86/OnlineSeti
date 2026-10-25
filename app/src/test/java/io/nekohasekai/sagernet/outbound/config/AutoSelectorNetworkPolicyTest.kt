package app.qthrone.mobile.outbound.config

import app.qthrone.mobile.outbound.types.AutoSelector
import app.qthrone.mobile.outbound.types.Hysteria
import app.qthrone.mobile.outbound.types.Tuic
import app.qthrone.mobile.outbound.types.WdttPlus
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSelectorNetworkPolicyTest {
    @Test
    fun cellularPrefersWdttPlusAndWifiLeavesNormalProfilesAvailable() {
        val cellular = AutoSelectorPlanner(SelectorStore { selectorGroup(wdttPlusAllowed = true) }, { 1_000L })
            .plan(99L, selector())
        assertEquals(listOf(2L, 3L, 1L), cellular.pool)

        val wifi = AutoSelectorPlanner(SelectorStore { selectorGroup(wdttPlusAllowed = false) }, { 1_000L })
            .plan(99L, selector())
        assertEquals(listOf(3L, 1L), wifi.pool)
    }

    @Test
    fun cellularTransitionRetestsWdttPlusEvenAfterFreshFailureOnWifi() {
        val selector = selector().apply { excludeUnavailable = true }
        val planner = AutoSelectorPlanner(SelectorStore { selectorGroup(wdttPlusAllowed = true, wdttLatency = -1) }, { 1_000L })

        assertEquals(emptyList<Long>(), planner.unmeasuredCandidates(99L, selector))
        assertEquals(listOf(2L), planner.unmeasuredCandidates(99L, selector, stale = listOf(2L)))
    }

    private fun selector() = AutoSelector().apply {
        gid = 7L
        buildLimit = 3
        poolCap = 10
        resultValidityMins = 60
        pool = mutableListOf(3L, 1L)
    }

    private fun selectorGroup(wdttPlusAllowed: Boolean, wdttLatency: Int = 500) = SelectorGroup(
        id = 7L,
        name = "test",
        landingProxyId = -1L,
        frontProxyId = -1L,
        landingProxy = null,
        frontProxy = null,
        members = listOf(
            SelectorMember(1L, Hysteria(), 100, 990, ""),
            SelectorMember(2L, WdttPlus(), wdttLatency, 990, ""),
            SelectorMember(3L, Tuic(), 20, 990, ""),
        ),
        wdttPlusAllowed = wdttPlusAllowed,
    )
}
