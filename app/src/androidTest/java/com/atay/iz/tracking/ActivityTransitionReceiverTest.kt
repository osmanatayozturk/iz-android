package com.atay.iz.tracking

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityTransitionReceiverTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val settings = TrackerSettings(base)
    private val starts = mutableListOf<Intent>()
    private val context = object : ContextWrapper(base) {
        override fun startForegroundService(service: Intent): ComponentName {
            starts += service
            return ComponentName(base, TrackingService::class.java)
        }
    }
    @Before fun prepare() {
        base.getSharedPreferences("tracking", Context.MODE_PRIVATE).edit().clear().commit()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS).forEach { automation.grantRuntimePermission(base.packageName, it) }
        settings.enabled = true
    }
    @After fun cleanup() {
        base.getSharedPreferences("tracking", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun send(vararg events: ActivityTransitionEvent) {
        val intent = Intent()
        SafeParcelableSerializer.serializeToIntentExtra(ActivityTransitionResult(events.toList()), intent,
            "com.google.android.location.internal.EXTRA_ACTIVITY_TRANSITION_RESULT")
        assertTrue(ActivityTransitionResult.hasResult(intent))
        ActivityTransitionReceiver().onReceive(context, intent)
    }
    @Test fun stillExitAndWalkingEnterAtSameInstantStartRecording() {
        val time = SystemClock.elapsedRealtimeNanos() - 1_000_000
        settings.currentActivity = DetectedActivity.STILL
        send(ActivityTransitionEvent(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_EXIT, time),
            ActivityTransitionEvent(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, time))
        assertEquals(1, starts.size)
        assertEquals("WALK", starts.single().getStringExtra("suggestedTransport"))
        assertEquals(DetectedActivity.WALKING, settings.currentActivity)
    }
    @Test fun distinctEventAtSameInstantInLaterDeliveryIsNotLostButDuplicatesAreIgnored() {
        val time = SystemClock.elapsedRealtimeNanos() - 1_000_000
        val exit = ActivityTransitionEvent(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_EXIT, time)
        val enter = ActivityTransitionEvent(DetectedActivity.ON_BICYCLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, time)
        send(exit)
        send(enter)
        send(exit, enter)
        assertEquals(1, starts.size)
        assertEquals("BICYCLE", starts.single().getStringExtra("suggestedTransport"))
    }
    @Test fun runningEnterSuggestsRunWithoutChangingWalkingEvents() {
        val time = SystemClock.elapsedRealtimeNanos() - 2_000_000
        send(ActivityTransitionEvent(DetectedActivity.RUNNING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, time))
        assertEquals("RUN", starts.single().getStringExtra("suggestedTransport"))
        assertEquals(DetectedActivity.RUNNING, settings.currentActivity)
        send(ActivityTransitionEvent(DetectedActivity.RUNNING, ActivityTransition.ACTIVITY_TRANSITION_EXIT, time + 1),
            ActivityTransitionEvent(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, time + 2))
        assertEquals(listOf("RUN", "WALK"), starts.map { it.getStringExtra("suggestedTransport") })
    }
    @Test fun oldOrDisabledTransitionsDoNotStartAService() {
        send(ActivityTransitionEvent(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            SystemClock.elapsedRealtimeNanos() - 180_000_000_000L))
        settings.enabled = false
        send(ActivityTransitionEvent(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            SystemClock.elapsedRealtimeNanos() - 1_000_000))
        assertTrue(starts.isEmpty())
    }
}
