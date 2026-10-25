package app.qthrone.mobile.database

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionOptionsLocalProfilesTest {
    @Test
    fun localProfilesRoundTripWithoutChangingOtherOptions() {
        val original = SubscriptionOptions(
            keepWorking = true,
            routingProvider = "happ",
            localProfileIds = listOf(12L, 34L),
        )

        val restored = SubscriptionOptions.parse(original.toJsonString())

        assertEquals(listOf(12L, 34L), restored.localProfileIds)
        assertEquals(true, restored.keepWorking)
        assertEquals("happ", restored.routingProvider)
    }

    @Test
    fun invalidLocalProfileIdsAreIgnored() {
        val restored = SubscriptionOptions.parse("""{"local_profile_ids":[4,-1,"bad",4]}""")

        assertEquals(listOf(4L), restored.localProfileIds)
    }
}
