package org.iz.navigation.ui

import org.iz.navigation.data.Transport
import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackFollowSelection
import org.iz.navigation.gpx.TrackSegment
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test

class GpxStartPermissionGateTest {
    private val request = GpxStartRequest(ImportedTrack(id = "synthetic", name = "Test", segments = listOf(
        TrackSegment("Part", listOf(WeatherCoordinate(0.0, 0.0), WeatherCoordinate(0.0, .01))))),
        TrackFollowSelection(), Transport.WALK, true, "session-a")

    @Test fun notificationDenialDoesNotBlockPreciseLocationStart() {
        val gate = GpxStartPermissionGate()
        assertTrue(gate.request(request, true, false, true)!!.notifications)
        assertEquals(request, gate.consume(true, "session-a"))
        assertNull(gate.consume(true, "session-a"))
    }

    @Test fun notificationDenialDoesNotPromptAgainOnTheNextExplicitStart() {
        val gate = GpxStartPermissionGate()
        gate.request(request, true, false, true)
        gate.consume(true, "session-a")
        assertFalse(gate.request(request, true, false, true)!!.needed)
        assertEquals(request, gate.consume(true, "session-a"))
    }

    @Test fun deniedPreciseLocationBlocksAndConsumesPendingStart() {
        val gate = GpxStartPermissionGate()
        assertTrue(gate.request(request, false, true, true)!!.location)
        assertNull(gate.consume(false, "session-a"))
        assertNull(gate.consume(true, "session-a"))
        assertFalse(gate.request(request, true, true, true)!!.needed)
        assertEquals(request, gate.consume(true, "session-a"))
    }

    @Test fun changedSessionDuringPermissionPromptRequiresFreshConfirmation() {
        val gate = GpxStartPermissionGate()
        gate.request(request, false, true, true)
        assertThrows(IllegalStateException::class.java) { gate.consume(true, "session-b") }
        assertNull(gate.consume(true, "session-a"))
    }

    @Test fun cancellationAndDuplicatePermissionCallbacksNeverStartAgain() {
        val gate = GpxStartPermissionGate()
        gate.request(request, true, true, true)
        assertNull(gate.request(request.copy(expectedReplacementKey = "session-b"), true, true, true))
        gate.cancel()
        assertNull(gate.consume(true, "session-a"))
    }

    @Test fun noNotificationPermissionPlatformStartsWithoutAnOptionalPrompt() {
        val gate = GpxStartPermissionGate()
        val fresh = request.copy(replaceExisting = false, expectedReplacementKey = null)
        assertFalse(gate.request(fresh, true, false, false)!!.needed)
        assertEquals(fresh, gate.consume(true, null))
    }
}
