package org.iz.navigation.osmcommunity

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.iz.navigation.MainActivity
import org.iz.navigation.R
import java.util.concurrent.TimeUnit

internal interface CommunityNotificationSink {
    fun permissionGranted(): Boolean
    fun show(accountId: Long, message: OsmMessageSummary)
    fun cancel(accountId: Long, messageId: Long)
    fun cancelAll()
}

internal interface CommunityNotificationScheduler {
    fun schedule(accountId: Long)
    fun cancel()
}

internal class CommunityNotifications(context: Context) : CommunityNotificationSink {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "OSM mesajları",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "OSM hesabınıza gelen yeni mesajlar"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    override fun permissionGranted(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(app).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    override fun show(accountId: Long, message: OsmMessageSummary) {
        if (permissionGranted()) manager.notify(tag(accountId, message.id), 0, buildNotification(accountId, message))
    }

    internal fun buildNotification(accountId: Long, message: OsmMessageSummary): Notification {
        val pending = PendingIntent.getActivity(app, 0, openMessageIntent(app, accountId, message.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val publicVersion = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OSM Topluluğu").setContentText("Yeni bir bildiriminiz var.").build()
        return NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OSM Topluluğu")
            .setContentText("Yeni bir mesajınız var. Açmak için dokunun.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
    }

    override fun cancel(accountId: Long, messageId: Long) { manager.cancel(tag(accountId, messageId), 0) }

    override fun cancelAll() {
        // Other app notifications (including an active journey) must remain untouched.
        manager.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    companion object {
        const val CHANNEL_ID = "osm_community_messages"
        const val ACTION_OPEN_MESSAGE = "org.iz.navigation.OSM_COMMUNITY_MESSAGE"
        const val EXTRA_MESSAGE_ID = "osm_message_id"
        const val EXTRA_ACCOUNT_ID = "osm_account_id"
        private const val TAG_PREFIX = "osm-community:"
        private fun tag(accountId: Long, messageId: Long) = "$TAG_PREFIX$accountId:$messageId"

        internal fun openMessageIntent(context: Context, accountId: Long, messageId: Long): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_MESSAGE
                data = Uri.parse("iz-osm-community://message/$accountId/$messageId")
                putExtra(EXTRA_MESSAGE_ID, messageId)
                putExtra(EXTRA_ACCOUNT_ID, accountId)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
    }
}

internal class WorkCommunityNotificationScheduler(context: Context) : CommunityNotificationScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    override fun schedule(accountId: Long) {
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request(accountId))
    }
    override fun cancel() { workManager.cancelUniqueWork(WORK_NAME) }

    companion object {
        const val WORK_NAME = "osm-community-inbox"
        internal fun request(accountId: Long): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<OsmCommunityNotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putLong(CommunityNotifications.EXTRA_ACCOUNT_ID, accountId).build())
                .build()
    }
}
