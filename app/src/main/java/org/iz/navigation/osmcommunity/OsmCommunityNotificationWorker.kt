package org.iz.navigation.osmcommunity

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.iz.navigation.IzApplication
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal class OsmCommunityNotificationWorker @JvmOverloads constructor(
    context: Context,
    parameters: WorkerParameters,
    private val repository: OsmCommunityRepository = (context.applicationContext as IzApplication).community,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getLong(CommunityNotifications.EXTRA_ACCOUNT_ID, -1)
        if (accountId <= 0) return Result.success()
        return try {
            repository.checkForNewMessages(accountId)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CommunitySessionChangedException) {
            Result.success()
        } catch (_: CommunityPermissionException) {
            repository.refreshNotificationSchedule()
            Result.success()
        } catch (failure: IOException) {
            val permanent = failure is CommunityHttpException && failure.statusCode in 400..499 && failure.statusCode != 429
            if (permanent || runAttemptCount >= 3) Result.failure() else Result.retry()
        }
    }
}
