package app.qthrone.mobile.bg.carrier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CarrierProcessCompatTest {
    @Test fun timedWaitReturnsFalseForRunningProcess() {
        val process = FakeProcess()
        assertFalse(CarrierProcessCompat.waitFor(process, 20, TimeUnit.MILLISECONDS))
        assertTrue(CarrierProcessCompat.isAlive(process))
    }

    @Test fun timedWaitReturnsTrueAfterProcessExits() {
        val process = FakeProcess()
        process.destroy()
        assertTrue(CarrierProcessCompat.waitFor(process, 20, TimeUnit.MILLISECONDS))
        assertFalse(CarrierProcessCompat.isAlive(process))
    }

    private class FakeProcess : Process() {
        private val alive = AtomicBoolean(true)
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { alive.set(false); return 0 }
        override fun exitValue(): Int {
            if (alive.get()) throw IllegalThreadStateException("still running")
            return 0
        }
        override fun destroy() { alive.set(false) }
    }
}
