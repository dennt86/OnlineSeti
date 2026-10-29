package app.qthrone.mobile.outbound

import app.qthrone.mobile.outbound.types.AutoSelector
import app.qthrone.mobile.outbound.types.Socks
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

    @Test
    fun hidesOnlyGeneratedOnlineSetiAccountSuffixFromDisplayName() {
        val outbound = Socks()

        outbound.name = "OnlineSeti+1748313736"
        assertEquals("OnlineSeti+", outbound.displayName())

        outbound.name = "OnlineSeti+"
        assertEquals("OnlineSeti+", outbound.displayName())
        outbound.name = "OnlineSeti+node-1748313736"
        assertEquals("OnlineSeti+node-1748313736", outbound.displayName())
        outbound.name = "Other+1748313736"
        assertEquals("Other+1748313736", outbound.displayName())
    }
}
