package org.iz.navigation.ui

import org.iz.navigation.data.Transport
import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackFollowSelection

internal data class GpxStartRequest(val track: ImportedTrack, val selection: TrackFollowSelection,
    val transport: Transport, val replaceExisting: Boolean, val expectedReplacementKey: String?)

internal data class GpxPermissionPrompt(val location: Boolean, val notifications: Boolean) {
    val needed: Boolean get() = location || notifications
}

internal class GpxStartPermissionGate {
    private var pending: GpxStartRequest? = null
    private var notificationPrompted = false

    fun request(value: GpxStartRequest, preciseGranted: Boolean, notificationsGranted: Boolean,
        canRequestNotifications: Boolean): GpxPermissionPrompt? {
        if (pending != null) return null
        pending = value
        val askNotifications = canRequestNotifications && !notificationsGranted && !notificationPrompted
        if (askNotifications) notificationPrompted = true
        return GpxPermissionPrompt(!preciseGranted, askNotifications)
    }

    fun consume(preciseGranted: Boolean, currentReplacementKey: String?): GpxStartRequest? {
        val value = pending
        pending = null
        if (!preciseGranted || value == null) return null
        check(!value.replaceExisting || value.expectedReplacementKey != null && value.expectedReplacementKey == currentReplacementKey) {
            "Açık yönlendirme değişti. GPX takibini başlatmak için yeniden onayla."
        }
        return value
    }

    fun cancel() { pending = null }
}
