package app.qthrone.mobile.bg.autoselector

/** Privacy-safe labels for selector diagnostics; never include a host, address, profile name, or raw error. */
internal object AutoSelectorDiagnostics {
    private val socksCode = Regex("(?i)socks5: request rejected, code=(\\d+)")

    fun memberLabel(tag: String): String =
        if (Regex("pool-\\d+-\\d+").matches(tag)) tag else "member"

    fun errorCategory(error: String): String {
        val socks = socksCode.find(error)?.groupValues?.getOrNull(1)
        if (socks != null) return "socks_rejected_$socks"
        return when {
            error.contains("connection reset by peer", ignoreCase = true) -> "connection_reset"
            error.contains("EOF", ignoreCase = true) -> "eof"
            error.contains("timeout", ignoreCase = true) || error.contains("deadline exceeded", ignoreCase = true) -> "timeout"
            error.contains("network is unreachable", ignoreCase = true) -> "network_unreachable"
            error.contains("no such host", ignoreCase = true) -> "dns_failure"
            error.contains("connection refused", ignoreCase = true) -> "connection_refused"
            error.contains("context canceled", ignoreCase = true) -> "cancelled"
            else -> "other"
        }
    }
}
