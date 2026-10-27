package app.qthrone.mobile.bg.carrier

import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.TimeUnit

/** Process timeout APIs were added in API 26; keep the carrier clients working on minSdk 24. */
internal object CarrierProcessCompat {
    fun isAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    @Throws(InterruptedException::class)
    fun waitFor(process: Process, timeout: Long, unit: TimeUnit): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return waitForApi26(process, timeout, unit)

        val timeoutNanos = unit.toNanos(timeout).coerceAtLeast(0L)
        val start = System.nanoTime()
        while (true) {
            if (!isAlive(process)) return true
            val remaining = timeoutNanos - (System.nanoTime() - start)
            if (remaining <= 0L) return !isAlive(process)
            TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10)))
        }
    }

    fun destroyForcibly(process: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForciblyApi26(process)
        else process.destroy()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun waitForApi26(process: Process, timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun destroyForciblyApi26(process: Process) {
        process.destroyForcibly()
    }
}
