package app.qthrone.mobile.outbound.config

import app.qthrone.mobile.outbound.types.AutoSelector
import app.qthrone.mobile.outbound.types.Hysteria
import app.qthrone.mobile.outbound.types.Tuic
import app.qthrone.mobile.outbound.types.WdttPlus
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSelectorNetworkPolicyTest {
    @Test
    fun cellularPrefersWdttPlusAndWifiKeepsItAsLastResort() {
        val cellular = AutoSelectorPlanner(SelectorStore { selectorGroup(wdttPlusPreferred = true) }, { 1_000L })
            .plan(99L, selector())
        assertEquals(listOf(2L, 3L, 1L), cellular.pool)

        val wifi = AutoSelectorPlanner(SelectorStore { selectorGroup(wdttPlusPreferred = false) }, { 1_000L })
            .plan(99L, selector())
        assertEquals(listOf(3L, 1L, 2L), wifi.pool)
        assertEquals(listOf(3L, 1L, 2L), wifi.build)
    }

    @Test
    fun cellularTransitionKeepsWdttPlusEligibleDespiteFreshFailureOnWifi() {
        val selector = selector().apply { excludeUnavailable = true }
        val cellularGroup = selectorGroup(wdttPlusPreferred = true, wdttLatency = -1)
        val planner = AutoSelectorPlanner(SelectorStore { cellularGroup }, { 1_000L })

        val plan = planner.plan(99L, selector)
        assertEquals(listOf(2L), plan.pool.take(1))
        val builtMembers = plan.build.mapNotNull { id -> cellularGroup.members.firstOrNull { it.id == id } }
        assertEquals(2L, effectivePinnedMember(1L, true, builtMembers))

        val wifiPlan = AutoSelectorPlanner(
            SelectorStore { selectorGroup(wdttPlusPreferred = false, wdttLatency = -1) }, { 1_000L },
        ).plan(99L, selector)
        assertEquals(listOf(3L, 1L, 2L), wifiPlan.pool)
    }

    @Test
    fun cellularPinsWdttPlusAgainstGenericWatchProbeButWifiReturnsToAutomaticSelection() {
        val members = selectorGroup(wdttPlusPreferred = true).members
        assertEquals(2L, effectivePinnedMember(1L, wdttPlusPreferred = true, builtMembers = members))
        assertEquals(1L, effectivePinnedMember(1L, wdttPlusPreferred = false, builtMembers = members))
        assertEquals(-1L, effectivePinnedMember(2L, wdttPlusPreferred = false, builtMembers = members))
        assertEquals(-1L, effectivePinnedMember(-1L, wdttPlusPreferred = false, builtMembers = members))
    }

    @Test
    fun wifiIncludesWdttAsEmergencyEvenWhenBuildLimitWouldOtherwiseDropIt() {
        val selector = selector().apply { buildLimit = 2; poolCap = 10; pool = mutableListOf(1L, 3L) }
        val group = selectorGroup(wdttPlusPreferred = false, wdttLatency = 0)
        val plan = AutoSelectorPlanner(SelectorStore { group }, { 1_000L }).plan(99L, selector)

        assertEquals(listOf(3L, 1L, 2L), plan.pool)
        assertEquals(listOf(3L, 1L, 2L), plan.build)
    }

    @Test
    fun wifiKeepsWdttEmergencyMemberWhenPoolCapWouldOtherwiseDropIt() {
        val selector = selector().apply { buildLimit = 2; poolCap = 2; pool = mutableListOf(1L, 3L) }
        val plan = AutoSelectorPlanner(
            SelectorStore { selectorGroup(wdttPlusPreferred = false) }, { 1_000L },
        ).plan(99L, selector)

        assertEquals(listOf(3L, 1L, 2L), plan.pool)
        assertEquals(listOf(3L, 1L, 2L), plan.build)
    }

    @Test
    fun unmeasuredCellularWdttDoesNotBlockAutoSelectorStartup() {
        val selector = selector().apply { buildLimit = 2; poolCap = 10; pool = mutableListOf(1L, 3L) }
        val group = selectorGroup(wdttPlusPreferred = true, wdttLatency = 0)
        val plan = AutoSelectorPlanner(SelectorStore { group }, { 1_000L }).plan(99L, selector)

        assertEquals(listOf(2L, 1L), plan.build)
        assertEquals(false, plan.needsRanking)
    }

    private fun selector() = AutoSelector().apply {
        gid = 7L
        buildLimit = 3
        poolCap = 10
        resultValidityMins = 60
        pool = mutableListOf(3L, 1L)
    }

    private fun selectorGroup(wdttPlusPreferred: Boolean, wdttLatency: Int = 500) = SelectorGroup(
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
        wdttPlusAllowed = true,
        wdttPlusPreferred = wdttPlusPreferred,
    )
}
