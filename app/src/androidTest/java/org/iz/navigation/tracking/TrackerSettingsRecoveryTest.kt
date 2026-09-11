package org.iz.navigation.tracking

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TrackerSettingsRecoveryTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val isolatedName = "tracking-recovery-test-${UUID.randomUUID()}"
    private lateinit var preferences: SharedPreferences
    private lateinit var settings: TrackerSettings

    @Before fun prepare() {
        preferences = base.getSharedPreferences(isolatedName, Context.MODE_PRIVATE)
        settings = TrackerSettings(object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        })
    }

    @After fun cleanup() { base.deleteSharedPreferences(isolatedName) }

    @Test fun legacyEnabledPreferenceSurvivesWithoutPretendingRegistrationSucceeded() {
        preferences.edit().putBoolean("enabled", true).commit()
        assertTrue(settings.enabled)
        assertEquals(DetectionRegistrationState.RETRY_PENDING, settings.registrationState)
        assertEquals(0L, settings.lastRegistrationAt)
        settings.registrationState = DetectionRegistrationState.RETRY_PENDING
        settings.registrationError = "Temporary connection issue"
        assertTrue(settings.enabled)
    }

    @Test fun bootClearsOnlyActivitySessionMarkersAndKeepsTheRequestedSettings() {
        settings.enabled = true
        settings.stopMinutes = 17
        settings.currentActivity = 0
        settings.suppressedActivity = 0
        settings.registrationState = DetectionRegistrationState.READY
        settings.lastRegistrationAt = 123_000
        assertTrue(settings.acceptTransition(0, 0, 1_000, 2_000))
        assertFalse(settings.acceptTransition(0, 0, 1_000, 2_000))
        settings.resetActivitySessionAfterBoot()
        assertTrue(settings.enabled)
        assertEquals(17, settings.stopMinutes)
        assertEquals(-1, settings.currentActivity)
        assertEquals(-1, settings.suppressedActivity)
        assertEquals(0L, settings.lastTransitionNanos)
        assertEquals(DetectionRegistrationState.RETRY_PENDING, settings.registrationState)
        assertEquals(123_000L, settings.lastRegistrationAt)
        assertTrue(settings.acceptTransition(0, 0, 1_000, 2_000))
    }

    @Test fun normalSettingsReadsDoNotClearAManuallyFinishedActivitySuppression() {
        settings.enabled = true
        settings.suppressedActivity = 7
        settings.registrationState = DetectionRegistrationState.READY
        assertTrue(settings.enabled)
        assertEquals(DetectionRegistrationState.READY, settings.registrationState)
        assertEquals(7, settings.suppressedActivity)
    }
}
