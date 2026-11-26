package org.iz.navigation.lean

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HandlebarPhysicalTimeMetadataTest {
    @Test
    fun liveFusedTimeMetadataKeepsItsCategoryThroughSanitizedRoomRoundTrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = requireTimingQa(emulator = false)
        assertEquals("SM-S936B", Build.MODEL)
        assertFalse(requireNotNull(context.getSystemService(KeyguardManager::class.java)).isKeyguardLocked)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val client = LocationServices.getFusedLocationProviderClient(context)
            val observed = Channel<Observation>(capacity = 64)
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val delivered = SystemClock.elapsedRealtimeNanos()
                    val original = result.lastLocation
                    if (original == null) {
                        observed.trySend(Observation.Skipped("EMPTY_CALLBACK"))
                        return
                    }
                    @Suppress("DEPRECATION")
                    val skip = when {
                        original.isFromMockProvider -> "MOCK"
                        original.elapsedRealtimeNanos <= 0L || original.time <= 0L -> "INVALID_TIMESTAMPS"
                        else -> null
                    }
                    if (skip != null) {
                        observed.trySend(Observation.Skipped(skip))
                        return
                    }
                    val reported = original.hasElapsedRealtimeUncertaintyNanos()
                    val uncertainty = original.elapsedRealtimeUncertaintyNanos.takeIf { reported }
                    val callbackCategory = category(reported, uncertainty)
                    // Only time metadata is transferred out of the platform callback. Other fields are synthetic.
                    val safe = Location("fused").apply {
                        latitude = .5
                        longitude = .5
                        time = original.time
                        elapsedRealtimeNanos = original.elapsedRealtimeNanos
                        if (uncertainty != null) elapsedRealtimeUncertaintyNanos = uncertainty
                        accuracy = 1f
                        speed = 12f
                        speedAccuracyMetersPerSecond = .2f
                        bearing = 90f
                        bearingAccuracyDegrees = 1f
                    }
                    val sameMetadata = reported == safe.hasElapsedRealtimeUncertaintyNanos() &&
                        (!reported || uncertainty?.toBits() == safe.elapsedRealtimeUncertaintyNanos.toBits())
                    val receipt = result.singleFixReceipt(delivered)
                    val converted = safe.toLeanFix(receipt)
                    if (converted == null) observed.trySend(Observation.Skipped("CONVERSION_REJECTED"))
                    else observed.trySend(Observation.Fix(callbackCategory, converted, sameMetadata, receipt != null,
                        delivered - safe.elapsedRealtimeNanos))
                }
            }
            try {
                scenario.onActivity { activity ->
                    activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onPause(owner: LifecycleOwner) {
                            client.removeLocationUpdates(callback)
                            observed.close()
                        }
                    })
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
                        .setMaxUpdateAgeMillis(0)
                        .setMaxUpdateDelayMillis(0)
                        .build()
                    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                        .addOnFailureListener { observed.close(IllegalStateException("Fused registration failed")) }
                }
                runBlocking(Dispatchers.IO) {
                    val observations = mutableListOf<Observation>()
                    withTimeoutOrNull(12_000) {
                        for (sample in observed) observations.add(sample)
                    }
                    val samples = observations.filterIsInstance<Observation.Fix>()
                    val skips = observations.filterIsInstance<Observation.Skipped>().groupingBy { it.reason }.eachCount()
                    val summary = JSONObject()
                        .put("status", if (samples.isEmpty()) "NO_USABLE_FIX" else "OBSERVED")
                        .put("callbacks", observations.size)
                        .put("sampleCount", samples.size)
                        .put("exclusions", JSONObject(skips))
                    if (samples.isNotEmpty()) {
                        val ordered = samples.sortedBy { it.safeFix.elapsedNanos }
                        val oldest = ordered.first().safeFix
                        val records = ordered.map { it.safeFix }
                        assertTrue(ordered.all { it.sameMetadata })
                        val categories = ordered.map { it.callbackCategory }
                        val converted = records.map(::recordCategory)
                        assertEquals(categories, converted)
                        withTimingRoom(context) { db, store ->
                            val trip = Journey(transport = Transport.MOTORCYCLE, startedAt = oldest.utcMillis)
                            db.diaryDao().save(trip)
                            val token = requireNotNull(store.begin(trip.id, handlebarTimingCalibration,
                                "qa-boot", oldest.utcMillis, oldest.elapsedNanos))
                            assertTrue(store.append(token, records))
                            val decoded = readTimingChunks(db, token, 1).filterIsInstance<LeanGnssRecord>()
                            assertEquals(records, decoded)
                            val persisted = decoded.map(::recordCategory)
                            assertEquals(categories, persisted)
                            val intervals = samples.zipWithNext { a, b -> b.safeFix.elapsedNanos - a.safeFix.elapsedNanos }
                            summary.put("platformCallback", JSONObject(categories.groupingBy { it }.eachCount()))
                                .put("convertedFix", JSONObject(converted.groupingBy { it }.eachCount()))
                                .put("roomReadback", JSONObject(persisted.groupingBy { it }.eachCount()))
                                .put("threeStagesCompared", decoded.size)
                                .put("singleFixCallbacks", samples.count { it.single })
                                .put("singleFixWithin250msCount", samples.count { it.single && it.ageNs in 0L..250_000_000L })
                                .put("ageOver2s", samples.count { it.ageNs > 2 * TIMING_SECOND })
                                .put("negativeAge", samples.count { it.ageNs < 0L })
                                .put("receiptPersisted", decoded.count { it.singleFixReceivedElapsedNanos != null })
                                .put("callbackAgeMs", spanSummary(samples.map { it.ageNs }))
                                .put("successiveFixIntervalMs", spanSummary(intervals.filter { it > 0L }))
                                .put("nonIncreasingIntervals", intervals.count { it <= 0L })
                        }
                    }
                    instrumentation.sendStatus(2, Bundle().apply { putString("qaGpsTimingSummary", summary.toString()) })
                }
            } finally {
                client.removeLocationUpdates(callback)
                observed.cancel()
            }
        }
    }

    private sealed interface Observation {
        data class Skipped(val reason: String) : Observation
        data class Fix(
            val callbackCategory: String, val safeFix: LeanGnssRecord, val sameMetadata: Boolean,
            val single: Boolean, val ageNs: Long,
        ) : Observation
    }

    private fun spanSummary(nanos: List<Long>): JSONObject {
        val values = nanos.map { it / 1_000_000.0 }
        val result = JSONObject().put("count", values.size)
        if (values.isNotEmpty()) result.put("min", values.min()).put("mean", values.average()).put("max", values.max())
        return result
    }

    private fun recordCategory(fix: LeanGnssRecord) =
        if (fix.invalidElapsedUncertainty) "NONFINITE"
        else category(fix.elapsedUncertaintyNanos != null, fix.elapsedUncertaintyNanos)

    private fun category(present: Boolean, value: Double?): String = when {
        !present -> "ABSENT"
        value == null || !value.isFinite() -> "NONFINITE"
        value == 0.0 -> "REPORTED_ZERO"
        value in 0.0..100_000_000.0 -> "REPORTED_WITHIN_100MS"
        else -> "REPORTED_OUTSIDE_RANGE"
    }
}
