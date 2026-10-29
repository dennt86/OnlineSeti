package app.qthrone.mobile.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryPolicyTest {
    @Test
    fun resetsOnlyWhenValidationReturnsAfterAnOutage() {
        assertFalse(NetworkRecoveryPolicy.shouldResetConnections(null, true))
        assertFalse(NetworkRecoveryPolicy.shouldResetConnections(true, true))
        assertFalse(NetworkRecoveryPolicy.shouldResetConnections(false, false))
        assertTrue(NetworkRecoveryPolicy.shouldResetConnections(false, true))
    }

    @Test
    fun proxyFailureBurstResetsOnceAndHonorsCooldown() {
        val policy = ProxyFailureRecoveryPolicy(failureThreshold = 3, windowMillis = 1_000, cooldownMillis = 5_000)

        assertFalse(policy.shouldReset(100))
        assertFalse(policy.shouldReset(200))
        assertTrue(policy.shouldReset(300))
        assertFalse(policy.shouldReset(400))
        assertFalse(policy.shouldReset(500))
        assertFalse(policy.shouldReset(600))
        assertFalse(policy.shouldReset(5_300))
        assertFalse(policy.shouldReset(5_400))
        assertTrue(policy.shouldReset(5_500))
    }
}
