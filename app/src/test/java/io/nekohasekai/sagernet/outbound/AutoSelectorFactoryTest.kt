package app.qthrone.mobile.outbound

import app.qthrone.mobile.outbound.types.AutoSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSelectorFactoryTest {
    @Test
    fun automaticProfileIsAlwaysRestoredAsSupported() {
        val profile = OutboundFactory.newByType("autoselector")

        assertFalse(profile.invalid)
        assertTrue(profile is AutoSelector)
        val selector = profile as AutoSelector
        assertEquals(AutoSelector.STICKY_TOLERANCE_MS, selector.toleranceMs)
        assertFalse(selector.interruptOnSwitch)
    }
}
