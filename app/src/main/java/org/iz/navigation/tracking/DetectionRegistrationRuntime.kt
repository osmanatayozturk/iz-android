package org.iz.navigation.tracking

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.tasks.await

/** Shared by all controller instances, the foreground activity, and restoration workers. */
internal object DetectionRegistrationRuntime {
    @Volatile private var coordinator: DetectionRegistrationCoordinator? = null

    fun get(context: Context): DetectionRegistrationCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: create(context.applicationContext).also { coordinator = it }
    }

    private fun create(context: Context): DetectionRegistrationCoordinator {
        val settings = TrackerSettings(context)
        val store = object : DetectionRegistrationStore {
            override var requestedEnabled: Boolean
                get() = settings.enabled
                set(value) { settings.enabled = value }
            override var registrationState: DetectionRegistrationState
                get() = settings.registrationState
                set(value) { settings.registrationState = value }
            override var registrationError: String?
                get() = settings.registrationError
                set(value) { settings.registrationError = value }
            override var lastRegistrationAt: Long
                get() = settings.lastRegistrationAt
                set(value) { settings.lastRegistrationAt = value }
            override var lastRegistrationAttemptAt: Long
                get() = settings.lastRegistrationAttemptAt
                set(value) { settings.lastRegistrationAttemptAt = value }
        }
        val gateway = object : DetectionRegistrationGateway {
            @SuppressLint("MissingPermission")
            override suspend fun register() {
                val transitions = listOf(DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE,
                    DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.STILL).flatMap { activity ->
                    listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { type ->
                        ActivityTransition.Builder().setActivityType(activity).setActivityTransition(type).build()
                    }
                }
                ActivityRecognition.getClient(context).requestActivityTransitionUpdates(
                    ActivityTransitionRequest(transitions), TrackingController.transitionIntent(context)).await()
                ExpirationWorker.schedule(context)
            }

            @SuppressLint("MissingPermission")
            override suspend fun unregister() {
                ActivityRecognition.getClient(context).removeActivityTransitionUpdates(
                    TrackingController.transitionIntent(context)).await()
            }
        }
        return DetectionRegistrationCoordinator(store, gateway, object : DetectionRetryScheduler {
            override fun schedule() = DetectionRetryWorker.schedule(context)
            override fun cancel() = DetectionRetryWorker.cancel(context)
        }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            { TrackingController.detectionPermissionError(context) })
    }
}
