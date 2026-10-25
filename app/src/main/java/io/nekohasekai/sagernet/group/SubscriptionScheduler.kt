package app.qthrone.mobile.group

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.multiprocess.RemoteWorkerService
import com.google.common.util.concurrent.ListenableFuture
import app.qthrone.mobile.R
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The automatic subscription update (mainwindow_setup.cpp:1096-1109 on PeriodicRunner): sub_auto_update is
 * sign-encoded minutes and only takes effect from 30; each run stamps sub_auto_update_last first, then
 * RefreshAll(onlyAllowed = true) in the :bg queue.
 */
object SubscriptionScheduler {

    const val MIN_INTERVAL_MINUTES = 30

    private const val WORK_NAME = "SubscriptionScheduler"

    /** The per-group updater of v11 and older, whose worker class is gone. */
    private const val LEGACY_WORK_NAME = "SubscriptionUpdater"

    private const val KEY_INTERVAL = "sub_auto_update"

    /**
     * (Re)schedules the periodic update from sub_auto_update, or cancels it below [MIN_INTERVAL_MINUTES]. Call it
     * after the interval changes; [keepExisting] (app start) leaves an already scheduled update alone.
     */
    fun schedule(keepExisting: Boolean = false) {
        runOnDefaultDispatcher {
            val workManager = RemoteWorkManager.getInstance(app)
            val interval = DataStore.subAutoUpdate
            try {
                workManager.cancelUniqueWork(LEGACY_WORK_NAME).await()
                if (interval < MIN_INTERVAL_MINUTES) {
                    workManager.cancelUniqueWork(WORK_NAME).await()
                    return@runOnDefaultDispatcher
                }
                val period = interval * 60L
                val now = System.currentTimeMillis() / 1000
                val initialDelay = (DataStore.subAutoUpdateLast + period - now).coerceIn(0L, period)
                val request = PeriodicWorkRequest.Builder(UpdateTask::class.java, interval.toLong(), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(initialDelay, TimeUnit.SECONDS)
                    .setInputData(
                        // The worker runs in the :bg process, where the queue lives.
                        Data.Builder()
                            .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, app.packageName)
                            .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
                            .putInt(KEY_INTERVAL, interval)
                            .build()
                    )
                    .build()
                val policy = if (keepExisting) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE
                workManager.enqueueUniquePeriodicWork(WORK_NAME, policy, request).await()
            } catch (e: Throwable) {
                Logs.w("SubscriptionScheduler: scheduling failed", e)
            }
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }, { it.run() })
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : RemoteCoroutineWorker(appContext, params) {
        override suspend fun doRemoteWork(): Result {
            val interval = DataStore.subAutoUpdate
            // The setting changed without a reschedule (e.g. a restored backup): follow it from now on.
            if (inputData.getInt(KEY_INTERVAL, interval) != interval) schedule()
            if (interval < MIN_INTERVAL_MINUTES) return Result.success()
            val now = System.currentTimeMillis() / 1000
            // A run within half an interval of the last one is a re-enqueue, not a new period.
            if (now - DataStore.subAutoUpdateLast < interval * 60L / 2) return Result.success()
            // Recorded before the run, so a slow job cannot double-fire (PeriodicRunner.cpp:36-49).
            DataStore.subAutoUpdateLast = now
            Logs.i(app.getString(R.string.subs_auto_update_running, app.getString(R.string.subs_auto_update_task)))
            SubscriptionQueue.refreshAll(onlyAllowed = true, foreground = false)
            SubscriptionQueue.awaitIdle()
            return Result.success()
        }
    }
}
