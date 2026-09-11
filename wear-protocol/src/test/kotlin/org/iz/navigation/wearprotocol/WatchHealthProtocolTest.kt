package org.iz.navigation.wearprotocol

import org.junit.Assert.*
import org.junit.Test

class WatchHealthProtocolTest {
    @Test fun measurementsAndAcknowledgementRetainIdentityAndActualTime() {
        val batch = HealthBatch("watch", "session", listOf(HealthReading(1, HealthReadingType.HEART_RATE,
            1000, 1000, 83.0), HealthReading(2, HealthReadingType.STEPS, 1000, 2000, 3.0)))
        assertEquals(batch, WatchHealthProtocol.decode(WatchHealthProtocol.encode(batch)))
        val ack = HealthAck("session", 2, false)
        assertEquals(ack, WatchHealthProtocol.decode(WatchHealthProtocol.encode(ack)))
    }
    @Test fun clockAnchorUsesMonotonicCaptureTimeAndRejectsSlowHandshake() {
        val anchor = HealthClockAnchor.create(100_000, 1000, 1100)!!
        assertEquals(100_900L, anchor.epochAt(2000))
        assertNull(HealthClockAnchor.create(100_000, 1000, 7000))
        assertNull(HealthClockAnchor.create(100_000, 1100, 1000))
    }
    @Test fun slowPhoneProcessingDoesNotPlaceSensorReadingsInPhonesFuture() {
        val anchor = HealthClockAnchor.create(101_500, 1000, 2540)!!
        assertEquals(101_500L, anchor.epochAt(2540))
        assertEquals(101_960L, anchor.epochAt(3000))
        assertTrue(anchor.discontinuous(HealthClockAnchor(91_500, 2540), 3000))
        assertFalse(anchor.discontinuous(HealthClockAnchor(101_600, 2540), 3000))
    }
    @Test fun corruptTruncatedOrOversizedFramesCannotPartlyImport() {
        val bytes = WatchHealthProtocol.encode(HealthHello("r", "watch", "Watch8", null))
        for (cut in bytes.indices) assertNull(WatchHealthProtocol.decode(bytes.copyOf(cut)))
        assertNull(WatchHealthProtocol.decode(bytes + byteArrayOf(0)))
        assertNull(WatchHealthProtocol.decode(ByteArray(4097)))
        assertThrows(IllegalArgumentException::class.java) { WatchHealthProtocol.encode(
            HealthBatch("watch", "session", listOf(HealthReading(1, HealthReadingType.HEART_RATE, 1, 1, Double.NaN)))) }
    }
    @Test fun candidateProgressDoesNotOverwriteWholeJourneyDistanceAndLegacyOmitsNewField() {
        val value = WearSnapshot(2000, "trip", WearMode.WALK, 1000, true, 900_000,
            true, 1000.0, candidateProgressMeters = 20.0)
        assertEquals(value, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(value, 4)))
        val old = WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(value, 3))!!
        assertEquals(1000.0, old.distanceMeters, 0.0)
        assertNull(old.candidateProgressMeters)
    }
}
