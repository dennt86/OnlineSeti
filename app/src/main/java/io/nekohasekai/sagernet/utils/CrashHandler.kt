package app.qthrone.mobile.utils

import android.content.Intent
import android.util.Log
import com.jakewharton.processphoenix.ProcessPhoenix
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ui.BlankActivity
import moe.matsuri.nb4a.utils.LogRedactor

/** Logs an uncaught exception and restarts into [BlankActivity], which shares a redacted log export. */
object CrashHandler : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // note: libc / go panic is in android log
        val redactor = LogRedactor(false)
        val safeStackTrace = throwable.stackTraceToString().lineSequence()
            .joinToString("\n", transform = redactor::redactSecretsOnly)

        try {
            Log.e(thread.toString(), safeStackTrace)
        } catch (e: Exception) {
        }

        try {
            Logs.e(thread.toString())
            Logs.e(safeStackTrace)
        } catch (e: Exception) {
        }

        ProcessPhoenix.triggerRebirth(app, Intent(app, BlankActivity::class.java).apply {
            putExtra(BlankActivity.EXTRA_SEND_LOG, true)
        })
    }
}
