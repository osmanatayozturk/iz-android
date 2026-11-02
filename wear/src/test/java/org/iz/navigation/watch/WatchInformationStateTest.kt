package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test

/** S2 consumer-visible state conflicts; pure v6 presentation, no device or service doubles. */
class WatchInformationStateTest {
    private val now = 1_000_000L
    private fun navigation() = WearNavigationSummary("session", true, now, false, false, false,
        false, false, 10, "Sağa dön", 150.0, 2200.0, now + 600_000, "Hedef")
    private fun recording() = WearSnapshot(now, journeyId = "journey", mode = WearMode.WALK,
        startedAt = now - 60_000, recording = true, statsDeferred = true)

    @Test fun absentSnapshotWinsEvenWhenThePhoneIsDisconnected() {
        val frame = WatchSurfacePolicy.frame(null, false, now)
        assertEquals("Telefon verisi bekleniyor", frame.lines.first())
        assertFalse(frame.lines.any { it.contains("0,0 km") })
    }

    @Test fun loadingWithoutGuidanceStillHasANavigationSurface() {
        val snapshot = WearSnapshot(now, navigation = navigation().copy(guidance = false, loading = true,
            fixAt = null, gpsStale = true, instruction = "", arrivalAt = null))
        val frame = WatchSurfacePolicy.frame(snapshot, true, now)
        assertEquals(WatchSurfaceRoute.NAVIGATION, frame.route)
        assertEquals("Rota hazırlanıyor", frame.lines.first())
    }

    @Test fun arrivalWithoutGuidanceRemainsVisibleAndDoesNotEndRecording() {
        val snapshot = recording().copy(navigation = navigation().copy(guidance = false, arrived = true,
            instruction = "", arrivalAt = null))
        val frame = WatchSurfacePolicy.frame(snapshot, true, now)
        assertEquals(WatchSurfaceRoute.NAVIGATION, frame.route)
        assertEquals("Hedefe ulaşıldı", frame.lines.first())
        assertTrue(snapshot.recording)
    }

    @Test fun disconnectedPhoneOutranksEveryCachedRemoteOperation() {
        for (nav in listOf(navigation().copy(loading = true), navigation().copy(offRoute = true),
            navigation().copy(arrived = true), navigation().copy(simulation = true))) {
            val frame = WatchSurfacePolicy.frame(WearSnapshot(now, navigation = nav), false, now)
            assertEquals("Telefon bağlı değil", frame.lines.first())
            assertTrue(frame.lines.any { it.contains("Son alınan bilgi") })
            assertFalse(frame.lines.any { it == "Rota hazırlanıyor" || it == "Hedefe ulaşıldı" ||
                it == "Simülasyon · telefonda" || it == "Rota yeniden hesaplanıyor" })
        }
    }

    @Test fun expiredPhoneSnapshotIsNotDescribedAsAGpsFailure() {
        val snapshot = WearSnapshot(now - 30_000, navigation = navigation())
        val frame = WatchSurfacePolicy.frame(snapshot, true, now)
        assertEquals("Telefon bilgisi güncel değil", frame.lines.first())
        assertFalse(frame.lines.any { it == "GPS konumu bekleniyor" || it.contains("150 m") })
    }

    @Test fun reconnectingDoesNotMakeAnOldPhoneReportCurrent() {
        val snapshot = WearSnapshot(now - 30_000, navigation = navigation().copy(loading = true))
        assertEquals("Telefon bağlı değil", WatchSurfacePolicy.frame(snapshot, false, now).lines.first())
        assertEquals("Telefon bilgisi güncel değil", WatchSurfacePolicy.frame(snapshot, true, now).lines.first())
    }

    @Test fun currentPhoneReportWithNoFixShowsGpsWaiting() {
        val snapshot = WearSnapshot(now, navigation = navigation().copy(fixAt = null, gpsStale = true))
        val frame = WatchSurfacePolicy.frame(snapshot, true, now)
        assertEquals("GPS konumu bekleniyor", frame.lines.first())
        assertFalse(frame.lines.any { it.contains("150 m") || it.contains("2,2 km") })
    }

    @Test fun loadingAndOffRouteAreNotMislabelledAsGpsLoss() {
        val loading = navigation().copy(loading = true)
        assertEquals("Rota hazırlanıyor",
            WatchSurfacePolicy.frame(WearSnapshot(now, navigation = loading), true, now).lines.first())
        assertEquals("Rota dışındasın. Yeni rota bekleniyor.",
            WatchSurfacePolicy.frame(WearSnapshot(now, navigation = navigation().copy(offRoute = true)), true, now).lines.first())
    }

    @Test fun phoneReportAndGpsFixShowTheirOwnAges() {
        val snapshot = WearSnapshot(now - 2_000, navigation = navigation().copy(fixAt = now - 29_000))
        val frame = WatchSurfacePolicy.frame(snapshot, true, now)
        assertTrue(frame.lines.contains("Telefon bilgisi: 2 sn önce"))
        assertTrue(frame.lines.contains("GPS konumu: 29 sn önce"))
    }

    @Test fun recordingStopsClaimingCurrentStateAtTheSharedSnapshotBoundary() {
        val snapshot = recording()
        val fresh = WatchSurfacePolicy.frame(snapshot, true, now + 29_999)
        assertEquals("Kayıt", fresh.compact)
        assertEquals(now + 30_000, fresh.validUntil)
        assertEquals(now + 30_000, fresh.compactUntil)
        val expired = WatchSurfacePolicy.frame(snapshot, true, now + 30_000)
        assertEquals("Telefon bilgisi güncel değil", expired.lines.first())
        assertEquals("Son durum", expired.compact)
        assertFalse(expired.lines.any { it == "Kayıt sürüyor" || it.contains("0,0 km") })
    }

    @Test fun disconnectedRecordingDoesNotRetainATimedCurrentClaim() {
        val frame = WatchSurfacePolicy.frame(recording(), false, now)
        assertEquals("Telefon bağlı değil", frame.lines.first())
        assertEquals("Son durum", frame.compact)
        assertNull(frame.validUntil)
        assertNull(frame.compactUntil)
    }

    @Test fun recordingTimelineHasAnExplicitExpiredFallback() {
        val snapshot = recording()
        val frame = WatchSurfacePolicy.frame(snapshot, true, now)
        val timeline = WatchSurfaceTimeline.segments(snapshot, true, now, frame)
        assertEquals(2, timeline.size)
        assertEquals(now + 30_000, timeline[0].end)
        assertEquals("Telefon bilgisi güncel değil", timeline[1].frame.lines.first())
        assertEquals("Son durum", timeline[1].frame.compact)
        assertNull(timeline[1].end)
    }

    @Test fun gpsExpiryThenSnapshotExpiryHaveSeparateTimelineEntries() {
        val snapshot = WearSnapshot(now, navigation = navigation().copy(fixAt = now - 10_000))
        val frame = WatchSurfacePolicy.frame(snapshot, true, now)
        val timeline = WatchSurfaceTimeline.segments(snapshot, true, now, frame)
        assertEquals(3, timeline.size)
        assertEquals(now + 20_000, timeline[0].end)
        assertEquals("GPS konumu bekleniyor", timeline[1].frame.lines.first())
        assertEquals(now + 30_000, timeline[1].end)
        assertEquals("Telefon bilgisi güncel değil", timeline[2].frame.lines.first())
    }

    @Test fun complicationCannotKeepAnEtaAfterPhoneLossOrSnapshotExpiry() {
        val snapshot = WearSnapshot(now, navigation = navigation())
        assertEquals("Telefon bağlı değil", WatchSurfacePolicy.complication(snapshot, false, now).lines.first())
        assertEquals("İz’i aç", WatchSurfacePolicy.complication(snapshot, false, now).compact)
        assertEquals("Telefon bilgisi güncel değil",
            WatchSurfacePolicy.complication(snapshot, true, now + 30_000).lines.first())
        assertEquals("İz’i aç", WatchSurfacePolicy.complication(snapshot, true, now + 30_000).compact)
    }

    @Test fun actualPreparedZeroRemainsAQualifiedPastMeasurementWhenDisconnected() {
        val summary = WearCompletedSummary("completed", WearMode.WALK, now - 60_000, now,
            distanceMeters = 0.0, elapsedMillis = 60_000, stepCount = 0L)
        val frame = WatchSurfacePolicy.frame(WearSnapshot(now, completed = summary), false, now)
        assertEquals("Telefon bağlı değil", frame.lines.first())
        assertTrue(frame.lines.any { it.contains("0,0 km") })
        assertTrue(frame.lines.any { it.contains("Son alınan bilgi") })
    }

    @Test fun deferredStatisticsNeverTurnIntoNumericMeasurements() {
        val frame = WatchSurfacePolicy.frame(recording(), true, now)
        assertTrue(frame.lines.any { it.contains("hazırlanacak") })
        assertFalse(frame.lines.any { it.contains("0,0 km") || it.contains("0:00") || it.contains("Adım 0") })
    }
}
