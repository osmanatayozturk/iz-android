package com.atay.iz.osmcommunity

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunityNotificationsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun periodicRequestRequiresConnectivityAndCarriesOnlyAccountId() {
        val request = WorkCommunityNotificationScheduler.request(42)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(900_000L, request.workSpec.intervalDuration)
        assertEquals(42L, request.workSpec.input.getLong(CommunityNotifications.EXTRA_ACCOUNT_ID, -1))
        assertEquals(setOf(CommunityNotifications.EXTRA_ACCOUNT_ID), request.workSpec.input.keyValueMap.keys)
    }

    @Test fun notificationContainsNoMessageBodySubjectOrSenderAndOpensExactAccount() {
        val sink = CommunityNotifications(context)
        val secret = summary(77).copy(title = "private subject", fromName = "private sender")
        val notification = sink.buildNotification(42, secret)
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertFalse(text.contains("private"))
        assertFalse(title.contains("private"))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        val intent = CommunityNotifications.openMessageIntent(context, 42, 77)
        assertEquals("com.atay.iz.MainActivity", intent.component?.className)
        assertEquals(CommunityNotifications.ACTION_OPEN_MESSAGE, intent.action)
        assertEquals(42L, intent.getLongExtra(CommunityNotifications.EXTRA_ACCOUNT_ID, -1))
        assertEquals(77L, intent.getLongExtra(CommunityNotifications.EXTRA_MESSAGE_ID, -1))
        assertNotEquals(intent.data, CommunityNotifications.openMessageIntent(context, 43, 77).data)
    }

    @Test fun staleAccountWorkerCompletesWithoutFetchingAnotherAccountsMessages() = runBlocking {
        withRepository { repository, gateway ->
            val worker = worker(repository, 2)
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertTrue(gateway.cursors.isEmpty())
        }
    }

    @Test fun workerDoesNotAutomaticallyRetryPermanentRejection() = runBlocking {
        withRepository { repository, gateway ->
            gateway.pages = { _, _ -> throw CommunityHttpException(403) }
            assertEquals(ListenableWorker.Result.failure(), worker(repository, 1).doWork())
            assertEquals(1, gateway.cursors.size)
        }
    }

    @Test fun retryAfterCooldownSurvivesWorkerAndPreventsPrematureNetworkRequest() = runBlocking {
        withRepository { repository, gateway ->
            gateway.pages = { _, _ -> throw CommunityHttpException(429, 120_000) }
            assertEquals(ListenableWorker.Result.retry(), worker(repository, 1).doWork())
            assertEquals(ListenableWorker.Result.retry(), worker(repository, 1).doWork())
            assertEquals(1, gateway.cursors.size)
        }
    }

    private fun worker(repository: OsmCommunityRepository, accountId: Long) =
        TestListenableWorkerBuilder<OsmCommunityNotificationWorker>(context)
            .setInputData(Data.Builder().putLong(CommunityNotifications.EXTRA_ACCOUNT_ID, accountId).build())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String,
                    workerParameters: WorkerParameters): ListenableWorker =
                    OsmCommunityNotificationWorker(appContext, workerParameters, repository)
            }).build()

    private suspend fun withRepository(block: suspend (OsmCommunityRepository, FakeCommunityGateway) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, OsmCommunityDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gateway = FakeCommunityGateway()
            val repository = OsmCommunityRepository(context, FakeCommunitySessions(), gateway, db,
                RecordingCommunitySink(), RecordingCommunityScheduler(), { 1_000L }, scope)
            repository.initialize()
            block(repository, gateway)
        } finally { scope.cancel(); db.close() }
    }
}
