package com.atay.iz.tracking

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Retries the low-power subscription only; never starts a location foreground service. */
class DetectionRetryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val settings = TrackerSettings(applicationContext)
        if (!settings.enabled) return Result.success()
        suspendCancellableCoroutine<Unit> { continuation ->
            TrackingController(applicationContext).restoreDetection {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        return if (settings.enabled && settings.registrationState == DetectionRegistrationState.RETRY_PENDING)
            Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "activity-recognition-registration"
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DetectionRetryWorker>()
                    .setInitialDelay(30, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }
}
