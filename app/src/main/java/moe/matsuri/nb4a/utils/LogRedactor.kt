package moe.matsuri.nb4a.utils

/**
 * Best-effort redaction of exported log lines: URL paths/queries and credentials, secret and server-naming key/value
 * pairs, UUIDs, base64 keys, public IP addresses and, with [hideDestinations], domain names in connection and DNS lines.
 */
class LogRedactor(private val hideDestinations: Boolean) {

    private companion object {
        val AUTH_HEADER = Regex("""(?im)(\b(?:cookie|set-cookie|authorization|proxy-authorization)\s*:\s*)[^\r\n]+""")
        val URL = Regex("""\b([a-zA-Z][a-zA-Z0-9+.\-]*)://([^\s/@"'<>]*@)?([^\s/?#"'<>]+)([^\s"'<>]*)""")
        val SECRET = Regex(
            """(?i)("?)([\w\-]*(?:password|passwd|pass|secret|token|key|uuid|auth|cookie|username|user_name|user|login|psk|short_id|hash|credential|client_id|device_id|subscription)[\w\-]*)\1(\s*[:=]\s*)("(?:[^"\\]|\\.)*"|[^\s,;&}\]]+)"""
        )
        // The logged start config names the servers by domain as well as by IP.
        val SERVER = Regex(
            """(?i)("?)(server|server_name|sni|host|address|path|service_name)\1(\s*[:=]\s*)("(?:[^"\\]|\\.)*"|[^\s,;&}\]]+)"""
        )
        // The core logs the connected Wi-Fi ("ssid NAME", "SSID=NAME, BSSID=…"); route rules list them as arrays.
        val WIFI = Regex("""(?i)\b(b?ssid)(\s*[:=]\s*|\s+)("(?:[^"\\]|\\.)*"|[^\s,;]+)""")
        val WIFI_LIST = Regex("""(?i)("wifi_b?ssid"\s*:\s*)\[[^\]]*]""")
        val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
        val BASE64 = Regex("""(?<![A-Za-z0-9+/_\-])[A-Za-z0-9+/_\-]{32,}={0,2}(?![A-Za-z0-9+/=_\-])""")
        val HEX_SECRET = Regex("""\b(?=[0-9a-fA-F]{32,128}\b)(?=[0-9a-fA-F]*[a-fA-F])(?=[0-9a-fA-F]*\d)[0-9a-fA-F]{32,128}\b""")
        val IPV4 = Regex("""(?<![\d.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![\d.])""")
        val IPV6 = Regex("""(?<![0-9A-Za-z:])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?![0-9A-Za-z:])""")
        val DESTINATION_LINE = Regex("""(?i)connection (?:to|from)|domain|dns|exchang|lookup|resolv|sniff""")
        val DOMAIN = Regex("""(?<![\w.\-])(?:[a-zA-Z0-9](?:[a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+([a-z]{2,63})\.?(?![\w\-])""")
    }

    /** Redact credentials while retaining server addresses and other useful diagnostics. */
    fun redactSecretsOnly(line: String): String {
        // Debug JSON/base64 lines can trigger quadratic regex backtracking. Omit them whole,
        // rather than cutting a credential in half and accidentally exporting its suffix.
        if(line.length>4096) return "<oversized diagnostic line omitted>"
        var s = AUTH_HEADER.replace(line) { m -> m.groupValues[1] + "<redacted>" }
        s = URL.replace(s) { m ->
            val (scheme, credentials, host, rest) = m.destructured
            "$scheme://" + (if (credentials.isNotEmpty()) "<redacted>@" else "") + host +
                    (if (rest.isNotEmpty()) "/<redacted>" else "")
        }
        s = redactValues(SECRET, s)
        s = UUID.replace(s, "<uuid>")
        s = BASE64.replace(s) { m ->
            val v = m.value
            if (v.any(Char::isUpperCase) && v.any(Char::isLowerCase) && v.any(Char::isDigit)) "<key>" else v
        }
        s = HEX_SECRET.replace(s, "<key>")
        return s
    }

    fun redact(line: String): String {
        var s = redactSecretsOnly(line)
        if (s == "<oversized diagnostic line omitted>") return s
        s = redactValues(SERVER, s)
        s = WIFI_LIST.replace(s) { m -> m.groupValues[1] + "[\"<redacted>\"]" }
        s = WIFI.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "<redacted>" }
        s = IPV4.replace(s) { m ->
            val octets = m.groupValues.drop(1).map { it.toInt() }
            if (octets.any { it > 255 }) m.value else "<ip>"
        }
        s = IPV6.replace(s) { m ->
            val v = m.value
            if (!NGUtil.isIpv6Address(v)) v else "<ip>"
        }
        if (hideDestinations && DESTINATION_LINE.containsMatchIn(s)) {
            s = DOMAIN.replace(s) { m -> "<domain>.${m.groupValues[1]}" }
        }
        return s
    }

    private fun redactValues(pairs: Regex, s: String): String = pairs.replace(s) { m ->
        val (quote, key, separator, value) = m.destructured
        val redacted = if (value.startsWith("\"")) "\"<redacted>\"" else "<redacted>"
        "$quote$key$quote$separator$redacted"
    }

}
