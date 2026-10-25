package moe.matsuri.nb4a.utils

import org.junit.Assert.*
import org.junit.Test

class LogRedactorTest {
    private val redactor = LogRedactor(false)
    @Test(timeout=2000) fun oversizedTokenLinesCannotStallExportOrLeakSuffix() {
        val line="access_token="+"a".repeat(200000)+"secretSuffix"
        val result=redactor.redact(line)
        assertFalse(result.contains("secretSuffix"))
        assertTrue(result.length<100)
    }

    @Test fun hidesOpenConnectCredentialsInConfiguration() {
        val result = redactor.redact("""{"type":"openconnect","username":"alice","password":"sample-password","cookie":"webvpn=session-cookie; other=private"}""")
        for (secret in listOf("alice", "sample-password", "session-cookie", "other=private"))
            assertFalse("Export leaked $secret", result.contains(secret))
        assertTrue(result.contains("openconnect"))
    }

    @Test fun hidesCompleteHttpAuthenticationHeaders() {
        for (header in listOf("Authorization: Bearer sample-token", "Proxy-Authorization: Basic sample-credentials",
            "Cookie: webvpn=first-cookie; session=second-cookie", "Set-Cookie: session=third-cookie; Path=/")) {
            val result = redactor.redact(header)
            assertTrue(result.contains("<redacted>"))
            assertFalse(result.contains("sample-"))
            assertFalse(result.contains("-cookie"))
        }
    }
}
