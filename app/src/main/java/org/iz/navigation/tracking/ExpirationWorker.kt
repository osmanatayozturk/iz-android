package org.iz.navigation.tracking

import android.content.Context
import android.content.Intent
import androidx.work.*
import org.iz.navigation.data.DiaryRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock

class ExpirationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        TrackingCoordinator.mutex.withLock {
            val repository = DiaryRepository(applicationContext)
            val id = TrackingService.runningJourneyId
            val active = id?.let { repository.getJourney(it) }
            val now = System.currentTimeMillis()
            if (active != null && TrackingPolicy.automaticCandidateDeadline(active)?.let { now >= it } == true) {
                // The service rolls its window and keeps GPS listening for the same ongoing activity.
                TrackingService.refresh(applicationContext)
            } else if (active != null && TrackingPolicy.expired(active.expiresAt, now)) {
                val settings = TrackerSettings(applicationContext)
                settings.suppressedActivity = settings.currentActivity
                applicationContext.stopService(Intent(applicationContext, TrackingService::class.java))
            }
            repository.cleanupExpired(now)
        }
        Result.success()
    } catch (_: Exception) { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("temporary-route-cleanup",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ExpirationWorker>(15, TimeUnit.MINUTES).build())
        }

        fun at(context: Context, expiresAt: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork("next-route-expiration",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ExpirationWorker>()
                    .setInitialDelay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .build())
        }
    }
}
