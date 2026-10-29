package app.qthrone.mobile.utils

import java.util.ArrayDeque

/** Whether a physical uplink has returned after Android reported it unvalidated. */
object NetworkRecoveryPolicy {
    fun shouldResetConnections(previousValidated: Boolean?, validated: Boolean): Boolean =
        previousValidated == false && validated
}

/** Throttles recovery resets when the configured SOCKS peer starts rejecting many new streams. */
class ProxyFailureRecoveryPolicy(
    private val failureThreshold: Int = 6,
    private val windowMillis: Long = 5_000,
    private val cooldownMillis: Long = 30_000,
) {
    private val failures = ArrayDeque<Long>()
    private var lastResetAt: Long? = null

    @Synchronized
    fun shouldReset(nowMillis: Long): Boolean {
        while (failures.isNotEmpty() && nowMillis - failures.first() > windowMillis) failures.removeFirst()
        failures.addLast(nowMillis)
        if (failures.size < failureThreshold) return false
        val lastReset = lastResetAt
        if (lastReset != null && nowMillis - lastReset < cooldownMillis) {
            failures.clear()
            return false
        }
        failures.clear()
        lastResetAt = nowMillis
        return true
    }
}
