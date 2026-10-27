package app.qthrone.mobile.group

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RoutingCatalogUpdaterTest {
    @Test fun updatesOnFirstRunAndAfterTwelveHours() {
        val period = TimeUnit.HOURS.toSeconds(RoutingCatalogUpdater.INTERVAL_HOURS)
        assertTrue(RoutingCatalogUpdater.isDue(100_000L, 0L))
        assertTrue("clock rollback must trigger a refresh", RoutingCatalogUpdater.isDue(99_999L, 100_000L))
        assertFalse(RoutingCatalogUpdater.isDue(100_000L, 100_000L))
        assertFalse(RoutingCatalogUpdater.isDue(100_000L + period - 1, 100_000L))
        assertTrue(RoutingCatalogUpdater.isDue(100_000L + period, 100_000L))
    }
}
