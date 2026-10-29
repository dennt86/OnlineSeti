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

    @Test
    fun coreToleranceIsCappedToSupportedRange() {
        assertEquals(10_000, AutoSelector.coreToleranceMs(AutoSelector.STICKY_TOLERANCE_MS))
        assertEquals(650, AutoSelector.coreToleranceMs(650))
        assertEquals(0, AutoSelector.coreToleranceMs(-1))
    }
}
