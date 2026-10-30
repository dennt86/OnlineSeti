package app.qthrone.mobile.utils

import android.net.NetworkCapabilities
import app.qthrone.mobile.SagerNet

/** WDTT Plus is allowed only when Android's current default upstream is a cellular network. */
object WdttPlusNetworkPolicy {

    data class FallbackCandidate(val id: Long, val type: String, val usable: Boolean, val latencyMs: Int)

    /** Pure policy function kept separate so the fail-closed transport rule is straightforward to verify. */
    fun isCellularDefault(hasCellular: Boolean, hasWifi: Boolean): Boolean = hasCellular && !hasWifi

    /** The first observed default network matters too: an auto-selector may have started before Android reported it. */
    fun needsAutoSelectorRecheck(
        previousCellularDefault: Boolean?,
        cellularDefault: Boolean,
        defaultNetworkChanged: Boolean = false,
    ): Boolean = defaultNetworkChanged || previousCellularDefault == null || previousCellularDefault != cellularDefault

    /** A persisted manual WDTT pin must not survive startup on cellular, where WDTT is eligible. */
    fun shouldClearCellularWdttPin(cellularDefault: Boolean, pinnedId: Long, wdttProfileIds: Collection<Long>): Boolean =
        cellularDefault && pinnedId != -1L && pinnedId in wdttProfileIds

    /** Restore the WDTT profile only while the fallback selected by the network policy is still selected. */
    fun shouldRestoreCellularWdtt(
        cellularDefault: Boolean,
        selectedProfileId: Long,
        wdttProfileId: Long,
        fallbackProfileId: Long,
    ): Boolean = cellularDefault && wdttProfileId > 0L && fallbackProfileId > 0L &&
        wdttProfileId != fallbackProfileId && selectedProfileId == fallbackProfileId

    /** Set WDTT as the initial cellular preference only when the core has no existing pin to manage failover. */
    fun shouldPinCellularWdtt(
        cellularDefault: Boolean,
        selectedTag: String,
        pinnedTag: String,
        wdttTag: String,
        memberUsable: Boolean,
    ): Boolean = cellularDefault && wdttTag.isNotEmpty() && selectedTag != wdttTag &&
        pinnedTag.isEmpty() && memberUsable

    /**
     * Keep cellular failover stable: pin WDTT as the initial preference, then pin a selected
     * backup only after the currently pinned member has a confirmed dead/cooldown state.
     * A healthy backup remains pinned when WDTT recovers, avoiding a disruptive failback.
     */
    fun cellularPinTarget(
        phase: String,
        selectedTag: String,
        pinnedTag: String,
        wdttTag: String,
        wdttState: String,
        pinnedState: String,
        selectedState: String,
        allowInitialWdttPin: Boolean = true,
        stabilizeSelectedAfterFailover: Boolean = false,
    ): String? {
        if (wdttTag.isEmpty() || selectedTag.isEmpty() || selectedTag == pinnedTag) return null
        if (pinnedTag.isEmpty()) {
            // Anchor a successful failover before applying startup preference.
            // The failed member's probe may still look healthy for a short time.
            if (stabilizeSelectedAfterFailover) {
                return selectedTag.takeIf { selectedState == "ok" || selectedState == "degraded" }
            }
            // Initial cellular preference is a startup action. A later unpinned
            // selection must not be mistaken for startup and pulled back to WDTT.
            if (!allowInitialWdttPin) return null
            return wdttTag.takeIf { wdttState == "ok" || wdttState == "degraded" }
        }
        if (phase != "ready" || (pinnedState != "dead" && pinnedState != "cooldown")) return null
        return selectedTag.takeIf { selectedState == "ok" || selectedState == "degraded" }
    }

    /** Unknown, disconnected, Wi-Fi, Ethernet and other upstreams are denied. */
    fun isCellularDefault(): Boolean {
        // Before VpnService publishes its underlying network, Android's default network is the
        // physical uplink. Using only underlyingNetwork here incorrectly rejects the first start.
        val network = SagerNet.underlyingNetwork ?: SagerNet.connectivity.activeNetwork ?: return false
        val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return false
        return isCellularDefault(
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        )
    }

    /** Prefer the configured automatic selector on Wi-Fi, then fall back to the best usable normal profile. */
    fun wifiFallback(selectedId: Long, candidates: List<FallbackCandidate>): FallbackCandidate? =
        candidates.asSequence()
            .filter { it.id != selectedId && it.usable && it.type != "wdttplus" }
            .sortedWith(compareBy<FallbackCandidate> { if (it.type == "autoselector") 0 else 1 }
                .thenBy { when {
                    it.latencyMs > 0 -> 0
                    it.latencyMs == 0 -> 1
                    else -> 2
                } }
                .thenBy { if (it.latencyMs > 0) it.latencyMs else Int.MAX_VALUE })
            .firstOrNull()
}
