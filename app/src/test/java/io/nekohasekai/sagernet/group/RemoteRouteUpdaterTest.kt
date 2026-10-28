package app.qthrone.mobile.group

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRouteUpdaterTest {
    @Test fun periodicUpdateRetriesFailuresButAcceptsEmptyOrSuccessfulRuns() {
        assertFalse(RemoteRouteUpdater.shouldRetryUpdate(emptyList()))
        assertTrue(RemoteRouteUpdater.shouldRetryUpdate(listOf("Russia rules: timeout")))
    }
}
