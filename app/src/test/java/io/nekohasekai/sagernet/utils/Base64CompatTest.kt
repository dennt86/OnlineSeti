package app.qthrone.mobile.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class Base64CompatTest {
    @Test
    fun standardEncodingKeepsPadding() {
        assertEquals("", Base64Compat.encodeToString(byteArrayOf()))
        assertEquals("Zg==", Base64Compat.encodeToString("f".toByteArray()))
        assertEquals("Zm8=", Base64Compat.encodeToString("fo".toByteArray()))
        assertEquals("Zm9v", Base64Compat.encodeToString("foo".toByteArray()))
    }

    @Test
    fun urlSafeEncodingOmitsPadding() {
        assertEquals("-_8", Base64Compat.encodeToString(byteArrayOf(0xfb.toByte(), 0xff.toByte()), urlSafe = true, padding = false))
    }
}
