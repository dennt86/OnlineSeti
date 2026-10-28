package app.qthrone.mobile.ui

import app.qthrone.mobile.group.OnlinesetiAutoSetup
import app.qthrone.mobile.outbound.types.AutoSelector
import org.junit.Assert.*
import org.junit.Test

class OnlinesetiAutoSetupTest {
    @Test fun selectorReplacesManualSelectionFromItsSubscriptionOnly() {
        assertTrue(OnlinesetiAutoSetup.shouldActivateSelector(null, 13, null, 42))
        assertTrue(OnlinesetiAutoSetup.shouldActivateSelector(13, 13, 7, 42))
        assertTrue(OnlinesetiAutoSetup.shouldActivateSelector(9, 13, 42, 42))
        assertFalse(OnlinesetiAutoSetup.shouldActivateSelector(9, 13, 7, 42))
    }

    @Test fun selectorRepairsMissingOrEmptySubscriptionGroups() {
        assertTrue(OnlinesetiAutoSetup.needsGroupRepair(false, 0))
        assertTrue(OnlinesetiAutoSetup.needsGroupRepair(true, 0))
        assertTrue(OnlinesetiAutoSetup.needsGroupRepair(true, -1))
        assertFalse(OnlinesetiAutoSetup.needsGroupRepair(true, 1))
    }

    @Test fun selectorTracksSubscriptionAndKeepsFailoverEnabled() {
        val policy = OnlinesetiAutoSetup.policy(42)
        assertEquals(42L, policy.gid)
        assertEquals(10, policy.watchIntervalSec)
        assertEquals(60, policy.intervalSec)
        assertEquals(AutoSelector.STICKY_TOLERANCE_MS, policy.toleranceMs)
        assertEquals(3, policy.expected)
        assertEquals(3, policy.dialRetries)
        assertFalse(policy.interruptOnSwitch)
        assertFalse(policy.balance)
        assertTrue(policy.nameFilter.isEmpty())
        assertTrue(policy.countryFilter.isEmpty())
        assertEquals(-1L, policy.pinnedID)
    }

    @Test fun managedSelectorMigratesOldSmoothingDefaultButPreservesCustomTolerance() {
        val old = OnlinesetiAutoSetup.policy(42).apply { toleranceMs = 300 }
        assertTrue(OnlinesetiAutoSetup.migrateSmoothingPolicy(old))
        assertEquals(AutoSelector.STICKY_TOLERANCE_MS, old.toleranceMs)

        val customized = OnlinesetiAutoSetup.policy(42).apply { toleranceMs = 650 }
        assertFalse(OnlinesetiAutoSetup.migrateSmoothingPolicy(customized))
        assertEquals(650, customized.toleranceMs)
    }

    @Test fun staleManagedSelectorIsReboundAndOldMemberStateIsCleared() {
        val selector = OnlinesetiAutoSetup.policy(7).apply {
            pool.addAll(listOf(100, 101))
            poolRankedAt = 123
            lastBuilt.add(100)
            lastBuiltAt = 456
            pinnedID = 101
        }

        assertTrue(OnlinesetiAutoSetup.rebind(selector, 42))
        assertEquals(42L, selector.gid)
        assertTrue(selector.pool.isEmpty())
        assertEquals(0L, selector.poolRankedAt)
        assertTrue(selector.lastBuilt.isEmpty())
        assertEquals(0L, selector.lastBuiltAt)
        assertEquals(-1L, selector.pinnedID)
        assertFalse(OnlinesetiAutoSetup.rebind(selector, 42))
    }
}
