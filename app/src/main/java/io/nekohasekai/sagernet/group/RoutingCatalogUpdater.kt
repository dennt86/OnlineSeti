package app.qthrone.mobile.group

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.multiprocess.RemoteWorkerService
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.RouteRepo
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Refreshes the routing-rule catalog and downloadable route-profile list. */
object RoutingCatalogUpdater {
    const val INTERVAL_HOURS = 12L
    private const val WORK_NAME = "RoutingCatalogUpdate"
    private const val STARTUP_WORK_NAME = "RoutingCatalogUpdateOnStart"
    private val updateMutex = Mutex()

    fun schedule() {
        runOnDefaultDispatcher {
            try {
                val manager = RemoteWorkManager.getInstance(app)
                val args = Data.Builder()
                    .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, app.packageName)
                    .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
                    .build()
                val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                val periodic = PeriodicWorkRequest.Builder(UpdateTask::class.java, INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInputData(args)
                    .build()
                manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic).await()

                if (isDue(System.currentTimeMillis() / 1000, DataStore.routingCatalogUpdateLast)) {
                    val startup = OneTimeWorkRequest.Builder(UpdateTask::class.java)
                        .setConstraints(constraints)
                        .setInputData(args)
                        .build()
                    manager.enqueueUniqueWork(STARTUP_WORK_NAME, ExistingWorkPolicy.KEEP, startup).await()
                }
            } catch (e: Throwable) {
                Logs.w("RoutingCatalogUpdater: scheduling failed", e)
            }
        }
    }

    internal fun isDue(nowSeconds: Long, lastSuccessSeconds: Long): Boolean =
        lastSuccessSeconds <= 0L || nowSeconds < lastSuccessSeconds ||
            nowSeconds - lastSuccessSeconds >= TimeUnit.HOURS.toSeconds(INTERVAL_HOURS)

    class UpdateTask(appContext: Context, params: WorkerParameters) : RemoteCoroutineWorker(appContext, params) {
        override suspend fun doRemoteWork(): Result {
            return updateMutex.withLock {
                val now = System.currentTimeMillis() / 1000
                if (!isDue(now, DataStore.routingCatalogUpdateLast)) return@withLock Result.success()
                val error = withContext(Dispatchers.IO) { RouteRepo.refresh(requireRemoteList = true) }
                if (error != null) {
                    Logs.w("Routing catalog refresh failed: $error")
                    return@withLock Result.retry()
                }
                DataStore.routingCatalogUpdateLast = System.currentTimeMillis() / 1000
                Logs.i("Routing catalog and remote route profiles refreshed")
                Result.success()
            }
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try { cont.resume(get()) } catch (e: Throwable) { cont.resumeWithException(e) }
        }, { it.run() })
    }
}
