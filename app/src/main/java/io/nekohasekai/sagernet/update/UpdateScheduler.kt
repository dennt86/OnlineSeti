package app.qthrone.mobile.update

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
import app.qthrone.mobile.BuildConfig
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The Android-only daily update check (updateCheckAuto): a notification when a newer release exists. */
object UpdateScheduler {

    private const val WORK_NAME = "UpdateCheck"

    /**
     * (Re)schedules or cancels the daily check; [keepExisting] (app start) leaves a scheduled check alone. [enabled]
     * overrides updateCheckAuto from a change listener, which runs before the new value is stored.
     */
    fun schedule(keepExisting: Boolean = false, enabled: Boolean? = null) {
        runOnDefaultDispatcher {
            val workManager = RemoteWorkManager.getInstance(app)
            try {
                if (!BuildConfig.IN_APP_UPDATER || !(enabled ?: DataStore.updateCheckAuto)) {
                    workManager.cancelUniqueWork(WORK_NAME).await()
                    return@runOnDefaultDispatcher
                }
                val request = PeriodicWorkRequest.Builder(CheckTask::class.java, 1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(
                        // Runs in the :bg process like the other workers.
                        Data.Builder()
                            .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, app.packageName)
                            .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
                            .build()
                    )
                    .build()
                val policy = if (keepExisting) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE
                workManager.enqueueUniquePeriodicWork(WORK_NAME, policy, request).await()
            } catch (e: Throwable) {
                Logs.w("UpdateScheduler: scheduling failed", e)
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

    class CheckTask(appContext: Context, params: WorkerParameters) : RemoteCoroutineWorker(appContext, params) {
        override suspend fun doRemoteWork(): Result {
            if (!BuildConfig.IN_APP_UPDATER || !DataStore.updateCheckAuto) return Result.success()
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(applicationContext) }
            if (result is UpdateChecker.Result.Available) {
                val offer = result.offer
                if (offer.versionCode == 0L || offer.versionCode != DataStore.updateSkippedVersionCode) {
                    UpdateNotifications.available(applicationContext, offer)
                }
            }
            return Result.success()
        }
    }
}
