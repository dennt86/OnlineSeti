package app.qthrone.mobile.ui

import app.qthrone.mobile.group.OnlinesetiAutoSetup
import org.junit.Assert.*
import org.junit.Test

class OnlinesetiAutoSetupTest {
    @Test fun selectorReplacesManualSelectionFromItsSubscriptionOnly() {
        assertTrue(OnlinesetiAutoSetup.shouldActivateSelector(null, 42))
        assertTrue(OnlinesetiAutoSetup.shouldActivateSelector(42, 42))
        assertFalse(OnlinesetiAutoSetup.shouldActivateSelector(7, 42))
    }

    @Test fun selectorTracksSubscriptionAndKeepsFailoverEnabled() {
        val policy = OnlinesetiAutoSetup.policy(42)
        assertEquals(42L, policy.gid)
        assertEquals(10, policy.watchIntervalSec)
        assertEquals(60, policy.intervalSec)
        assertEquals(3, policy.expected)
        assertEquals(2, policy.dialRetries)
        assertTrue(policy.interruptOnSwitch)
        assertFalse(policy.balance)
        assertTrue(policy.nameFilter.isEmpty())
        assertTrue(policy.countryFilter.isEmpty())
        assertEquals(-1L, policy.pinnedID)
    }
}
