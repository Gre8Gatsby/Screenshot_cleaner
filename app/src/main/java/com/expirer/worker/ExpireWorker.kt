package com.expirer.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.expirer.core.ExpirerJni
import com.expirer.service.NotificationHelper
import java.util.concurrent.TimeUnit

class ExpireWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ExpireWorker"
        private const val PERIODIC_WORK_TAG = "screenshot_expire_periodic"
        private const val ONETIME_WORK_TAG = "screenshot_expire_onetime"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .build()

            // Run every 15 minutes (minimum allowable interval in WorkManager)
            val periodicRequest = PeriodicWorkRequestBuilder<ExpireWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
            Log.i(TAG, "Enqueued periodic ExpireWorker (every 15 mins)")
        }

        fun runOnce(context: Context) {
            val oneTimeRequest = OneTimeWorkRequestBuilder<ExpireWorker>()
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONETIME_WORK_TAG,
                ExistingWorkPolicy.REPLACE,
                oneTimeRequest
            )
            Log.i(TAG, "Enqueued one-time ExpireWorker")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "ExpireWorker starting auto-expiration run...")
        val initialized = ExpirerJni.init(applicationContext)
        if (!initialized) {
            Log.e(TAG, "ExpirerJni failed to initialize")
            return Result.retry()
        }

        try {
            val summary = ExpirerJni.executeExpiration()
            Log.i(
                TAG,
                "Expiration executed. Deleted: ${summary.deletedCount}, Freed: ${summary.freedBytes} bytes"
            )

            // Clean up deleted URIs from MediaStore
            for (uriStr in summary.deletedUris) {
                try {
                    val uri = Uri.parse(uriStr)
                    applicationContext.contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore cleanup error for $uriStr: ${e.message}")
                }
            }

            // Show summary notification if files were deleted
            if (summary.deletedCount > 0) {
                NotificationHelper.notifyCleanupSummary(
                    applicationContext,
                    summary.deletedCount,
                    summary.freedBytes
                )
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in ExpireWorker: ${e.message}", e)
            return Result.failure()
        }
    }
}

