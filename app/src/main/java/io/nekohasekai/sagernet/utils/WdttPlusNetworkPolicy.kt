package app.qthrone.mobile.utils

import android.net.NetworkCapabilities
import app.qthrone.mobile.SagerNet

/** WDTT Plus is allowed only when Android's current default upstream is a cellular network. */
object WdttPlusNetworkPolicy {

    data class FallbackCandidate(val id: Long, val type: String, val usable: Boolean, val latencyMs: Int)

    /** Pure policy function kept separate so the fail-closed transport rule is straightforward to verify. */
    fun isCellularDefault(hasCellular: Boolean, hasWifi: Boolean): Boolean = hasCellular && !hasWifi

    /** The first observed default network matters too: an auto-selector may have started before Android reported it. */
    fun needsAutoSelectorRecheck(previousCellularDefault: Boolean?, cellularDefault: Boolean): Boolean =
        previousCellularDefault == null || previousCellularDefault != cellularDefault

    /** A persisted manual WDTT pin must not survive startup on cellular, where WDTT is eligible. */
    fun shouldClearCellularWdttPin(cellularDefault: Boolean, pinnedId: Long, wdttProfileIds: Collection<Long>): Boolean =
        cellularDefault && pinnedId != -1L && pinnedId in wdttProfileIds

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
