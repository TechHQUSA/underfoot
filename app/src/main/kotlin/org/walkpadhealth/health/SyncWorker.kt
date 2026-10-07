package org.walkpadhealth.health

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.walkpadhealth.data.AppDb
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result =
        when (SyncSessions(AppDb.get(applicationContext).sessions(), HealthConnectGateway(applicationContext)).run()) {
            SyncResult.Done, SyncResult.Blocked -> Result.success()   // Blocked: re-enqueued when the app opens or permission is granted
            SyncResult.Retry -> Result.retry()
        }
}

object SyncScheduler {
    fun enqueue(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, req)   // a session saved while a run is in flight still gets its own run
    }
}
