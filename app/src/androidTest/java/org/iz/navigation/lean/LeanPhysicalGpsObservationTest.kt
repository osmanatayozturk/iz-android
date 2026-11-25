package org.iz.navigation.lean

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.MainActivity
import org.iz.navigation.tracking.ComparisonField
import org.iz.navigation.tracking.ComparisonPhase
import org.iz.navigation.tracking.ComparisonStreamSummary
import org.iz.navigation.tracking.LocationSourceComparison
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeanPhysicalGpsObservationTest {
    @Test fun fusedAndDirectGpsObserveTheSameStationaryWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        assertEquals(LEAN_DIAGNOSTIC_QA_PACKAGE, args.getString("qaTargetPackage"))
        assertEquals(LEAN_DIAGNOSTIC_QA_PACKAGE, context.packageName)
        assertEquals("confirmed", args.getString("stationaryPhone"))
        assertEquals("samsung", Build.MANUFACTURER.lowercase(java.util.Locale.ROOT))
        assertEquals("SM-S936B", Build.MODEL)
        assertFalse(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val lock = requireNotNull(context.getSystemService(KeyguardManager::class.java))
        assertFalse("Phone must remain unlocked for this foreground check", lock.isKeyguardLocked)
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertFalse(Manifest.permission.INTERNET in permissions)
        assertTrue("Precise location for the separate QA app", LocationSourceComparison.preciseGranted(context))

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            runBlocking {
                val comparison = LocationSourceComparison(context)
                val measurement = launch(start = CoroutineStart.LAZY) {
                    val result = withTimeout(19_000) { comparison.measure() }
                    val report = JSONObject()
                        .put("phase", result.phase.name)
                        .put("issue", result.issue?.name ?: JSONObject.NULL)
                        .put("elapsedMs", result.elapsedMillis)
                        .put("plannedMs", result.plannedMillis)
                        .put("registrationMs", result.registrationMillis)
                        .put("fused", summary(result.fused))
                        .put("directGps", summary(result.gps))
                    instrumentation.sendStatus(2, Bundle().apply { putString("qaGpsSummary", report.toString()) })
                    assertEquals("Both registrations must reach the shared window", ComparisonPhase.COMPLETE, result.phase)
                    assertEquals(10_000L, result.elapsedMillis)
                    assertEquals(result.plannedMillis, result.elapsedMillis)
                }
                val observer = object : DefaultLifecycleObserver {
                    override fun onPause(owner: LifecycleOwner) {
                        measurement.cancel()
                    }
                }
                scenario.onActivity { activity ->
                    check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    activity.lifecycle.addObserver(observer)
                }
                try {
                    measurement.start()
                    measurement.join()
                    assertFalse("Measurement was cancelled before the window ended", measurement.isCancelled)
                } finally {
                    scenario.onActivity { it.lifecycle.removeObserver(observer) }
                }
            }
        }
    }

    private fun summary(stream: ComparisonStreamSummary): JSONObject {
        val fields = JSONObject()
        for (field in ComparisonField.entries) {
            val quality = stream.fields.getValue(field)
            assertEquals(stream.accepted, quality.values.count + quality.invalid + quality.missing)
            val values = JSONObject().put("available", quality.values.count)
                .put("invalid", quality.invalid).put("missing", quality.missing)
            if (field in ERROR_ESTIMATE_FIELDS) {
                values.put("estimate", JSONObject()
                    .put("min", quality.values.minimum ?: JSONObject.NULL)
                    .put("mean", quality.values.mean ?: JSONObject.NULL)
                    .put("max", quality.values.maximum ?: JSONObject.NULL))
            }
            fields.put(field.name, values)
        }
        val interval = JSONObject()
            .put("count", stream.intervalMillis.count)
            .put("min", stream.intervalMillis.minimum ?: JSONObject.NULL)
            .put("max", stream.intervalMillis.maximum ?: JSONObject.NULL)
            .put("mean", stream.intervalMillis.mean ?: JSONObject.NULL)
        assertEquals(
            stream.received,
            stream.accepted + stream.invalidTime + stream.outsideWindow + stream.duplicates + stream.outOfOrder,
        )
        return JSONObject().put("callbackCount", stream.callbacks).put("received", stream.received)
            .put("accepted", stream.accepted).put("mock", stream.mock).put("beforeOrAfterWindow", stream.outsideWindow)
            .put("reportedAvailable", stream.available ?: JSONObject.NULL).put("sampleIntervalMs", interval)
            .put("fieldPresence", fields)
    }

    private companion object {
        val ERROR_ESTIMATE_FIELDS = setOf(
            ComparisonField.HORIZONTAL_ACCURACY, ComparisonField.SPEED_ACCURACY,
            ComparisonField.BEARING_ACCURACY, ComparisonField.TIME_UNCERTAINTY_MILLIS,
        )
    }
}
