package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.utils.WdttPlusNetworkPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WdttPlusNetworkPolicyTest {

    @Test
    fun firstObservedNetworkRequiresAutoSelectorRecheck() {
        assertTrue(WdttPlusNetworkPolicy.needsAutoSelectorRecheck(null, true))
        assertTrue(WdttPlusNetworkPolicy.needsAutoSelectorRecheck(null, false))
        assertFalse(WdttPlusNetworkPolicy.needsAutoSelectorRecheck(true, true))
        assertFalse(WdttPlusNetworkPolicy.needsAutoSelectorRecheck(false, false))
        assertTrue(WdttPlusNetworkPolicy.needsAutoSelectorRecheck(true, false))
        assertTrue(WdttPlusNetworkPolicy.needsAutoSelectorRecheck(false, true))
    }

    @Test
    fun sameCellularTypeStillRechecksAfterTheDefaultNetworkReconnects() {
        // A temporary lost callback clears observedNetwork but leaves the last transport type intact.
        // When mobile data returns, cellular -> cellular still represents a new upstream and must
        // rebuild the selector so WDTT Plus is restored to its cellular-first position.
        assertTrue(WdttPlusNetworkPolicy.needsAutoSelectorRecheck(
            previousCellularDefault = true,
            cellularDefault = true,
            defaultNetworkChanged = true,
        ))
        assertFalse(WdttPlusNetworkPolicy.needsAutoSelectorRecheck(
            previousCellularDefault = true,
            cellularDefault = true,
            defaultNetworkChanged = false,
        ))
    }
    @Test
    fun onlyCellularDefaultIsAllowed() {
        assertTrue(WdttPlusNetworkPolicy.isCellularDefault(hasCellular = true, hasWifi = false))
        assertFalse(WdttPlusNetworkPolicy.isCellularDefault(hasCellular = false, hasWifi = true))
        assertFalse(WdttPlusNetworkPolicy.isCellularDefault(hasCellular = false, hasWifi = false))
        assertFalse(WdttPlusNetworkPolicy.isCellularDefault(hasCellular = true, hasWifi = true))
    }

    @Test
    fun onlyPersistedWdttPinIsClearedOnCellular() {
        assertTrue(WdttPlusNetworkPolicy.shouldClearCellularWdttPin(true, 42L, listOf(42L, 43L)))
        assertFalse(WdttPlusNetworkPolicy.shouldClearCellularWdttPin(true, 7L, listOf(42L, 43L)))
        assertFalse(WdttPlusNetworkPolicy.shouldClearCellularWdttPin(false, 42L, listOf(42L, 43L)))
        assertFalse(WdttPlusNetworkPolicy.shouldClearCellularWdttPin(true, -1L, listOf(42L, 43L)))
    }

    @Test
    fun cellularRestoresWdttOnlyWhileItsAutomaticFallbackRemainsSelected() {
        assertTrue(WdttPlusNetworkPolicy.shouldRestoreCellularWdtt(true, 7L, 42L, 7L))
        assertFalse(WdttPlusNetworkPolicy.shouldRestoreCellularWdtt(false, 7L, 42L, 7L))
        assertFalse(WdttPlusNetworkPolicy.shouldRestoreCellularWdtt(true, 8L, 42L, 7L))
        assertFalse(WdttPlusNetworkPolicy.shouldRestoreCellularWdtt(true, 7L, 0L, 7L))
        assertFalse(WdttPlusNetworkPolicy.shouldRestoreCellularWdtt(true, 7L, 7L, 7L))
    }

    @Test
    fun doesNotRepinWdttWhenCoreAlreadyOwnsTheCellularFallbackPin() {
        assertTrue(WdttPlusNetworkPolicy.shouldPinCellularWdtt(
            cellularDefault = true,
            selectedTag = "backup",
            pinnedTag = "",
            wdttTag = "wdtt",
            memberUsable = true,
        ))
        assertFalse(WdttPlusNetworkPolicy.shouldPinCellularWdtt(
            cellularDefault = true,
            selectedTag = "backup",
            pinnedTag = "wdtt",
            wdttTag = "wdtt",
            memberUsable = true,
        ))
        assertFalse(WdttPlusNetworkPolicy.shouldPinCellularWdtt(
            cellularDefault = true,
            selectedTag = "backup",
            pinnedTag = "",
            wdttTag = "wdtt",
            memberUsable = false,
        ))
    }

    @Test
    fun cellularPinAnchorsInitialWdttAndOnlyMovesAfterConfirmedFailure() {
        fun target(phase: String, selected: String, pinned: String, wdttState: String,
                   pinnedState: String, selectedState: String) = WdttPlusNetworkPolicy.cellularPinTarget(
            phase, selected, pinned, "wdtt", wdttState, pinnedState, selectedState,
        )

        assertTrue(target("probing", "backup", "", "ok", "", "ok") == "wdtt")
        // A live failover with no explicit pin must be anchored to the working
        // backup before the startup preference check can send traffic back to WDTT.
        assertTrue(WdttPlusNetworkPolicy.cellularPinTarget(
            phase = "ready",
            selectedTag = "backup",
            pinnedTag = "",
            wdttTag = "wdtt",
            wdttState = "ok",
            pinnedState = "",
            selectedState = "ok",
            allowInitialWdttPin = false,
            stabilizeSelectedAfterFailover = true,
        ) == "backup")
        assertTrue(target("ready", "backup", "wdtt", "dead", "dead", "ok") == "backup")
        // A transiently selected but unqualified degraded member must not be pinned back
        // after a failover; keep the automatic selector free to choose a qualified route.
        assertTrue(WdttPlusNetworkPolicy.cellularPinTarget(
            phase = "ready",
            selectedTag = "wdtt",
            pinnedTag = "backup",
            wdttTag = "wdtt",
            wdttState = "degraded",
            pinnedState = "dead",
            selectedState = "degraded",
            selectedQualified = false,
        ) == null)
        // The core keeps this reason after the event. A later unrelated "best available"
        // selection must not be mistaken for a fresh failover and pinned by the app.
        assertTrue(WdttPlusNetworkPolicy.cellularPinTarget(
            phase = "ready",
            selectedTag = "backup",
            pinnedTag = "",
            wdttTag = "wdtt",
            wdttState = "ok",
            pinnedState = "",
            selectedState = "ok",
            allowInitialWdttPin = false,
            stabilizeSelectedAfterFailover = false,
        ) == null)
        // Do not fail back to WDTT on a transient recovery while the pinned backup is healthy.
        assertTrue(target("ready", "backup", "backup", "ok", "ok", "ok") == null)
        assertTrue(target("ready", "wdtt", "backup", "ok", "dead", "ok") == "wdtt")
        // Startup's untested WDTT is not a confirmed outage and must not pin a backup.
        assertTrue(target("probing", "backup", "", "untested", "", "ok") == null)
        assertTrue(target("ready", "backup", "wdtt", "dead", "dead", "untested") == null)
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
