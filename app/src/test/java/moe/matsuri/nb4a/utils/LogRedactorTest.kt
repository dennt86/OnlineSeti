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

    @Test fun hidesProxyCredentialsBeforeTheyAreWrittenToCoreLog() {
        val config = """{"outbounds":[{"type":"vless","server":"proxy.example","uuid":"00000000-0000-0000-0000-000000000001","password":"vless-password"},{"type":"socks","username":"socks-user","password":"socks-password"},{"type":"socks","user":"socks-user-id","password":"socks-user-id"},{"type":"tuic","token":"tuic-token","private_key":"private-key-material-1234567890"}],"hashes":["vk-hash-secret"],"vk_hash":"vk-hash-value","client_id":"private-client-id","device_id":"private-device-id","subscription_token":"private-subscription-token","sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}"""
        val result = redactor.redactSecretsOnly(config)
        for (secret in listOf("vless-password", "socks-user", "socks-password", "socks-user-id", "tuic-token", "private-key-material-1234567890", "vk-hash-secret", "vk-hash-value", "private-client-id", "private-device-id", "private-subscription-token", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "00000000-0000-0000-0000-000000000001"))
            assertFalse("Core log leaked $secret", result.contains(secret))
        assertTrue("Useful endpoint diagnostics should remain", result.contains("proxy.example"))
    }

    @Test fun oversizedConfigLinesAreOmittedBeforeCoreLogPersistence() {
        val line = "{\"password\":\"" + "secret".repeat(1000) + "\"}"
        val result = redactor.redactSecretsOnly(line)
        assertFalse(result.contains("secret"))
        assertTrue(result.contains("oversized diagnostic line omitted"))
    }

    @Test fun storedCoreLogsHideDnsDestinationsAndServerAddresses() {
        val privateLogRedactor = LogRedactor(true)
        val dnsLine = privateLogRedactor.redact("dns: lookup domain private.example.com")
        val configLine = privateLogRedactor.redact("{\"server\":\"proxy.example.com\",\"password\":\"secret-pass\"}")
        val localAddressLine = privateLogRedactor.redact("network: updated network environment: gateway 192.168.10.1")
        assertFalse(dnsLine.contains("private.example.com"))
        assertFalse(configLine.contains("proxy.example.com"))
        assertFalse(configLine.contains("secret-pass"))
        assertFalse(localAddressLine.contains("192.168.10.1"))
    }
}
