package app.qthrone.mobile.bg.autoselector

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSelectorDiagnosticsTest {
    @Test
    fun memberLabelsNeverExposeUnknownTags() {
        assertEquals("pool-2-7", AutoSelectorDiagnostics.memberLabel("pool-2-7"))
        assertEquals("member", AutoSelectorDiagnostics.memberLabel("profile-secret-name"))
        assertEquals("member", AutoSelectorDiagnostics.memberLabel("proxy.example.com:443"))
    }

    @Test
    fun errorsAreReducedToSafeCategories() {
        assertEquals("eof", AutoSelectorDiagnostics.errorCategory("connect to secret.example:443: EOF"))
        assertEquals("connection_reset", AutoSelectorDiagnostics.errorCategory("read tcp 10.0.0.1:4: connection reset by peer"))
        assertEquals("socks_rejected_4", AutoSelectorDiagnostics.errorCategory("socks5: request rejected, code=4"))
        assertEquals("other", AutoSelectorDiagnostics.errorCategory("token=secret"))
    }
}
