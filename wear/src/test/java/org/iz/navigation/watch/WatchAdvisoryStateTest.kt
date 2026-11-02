package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test

/** v7 phone-owned advisory facts; exercises actual presentation with no Android doubles. */
class WatchAdvisoryStateTest {
    private val now = 1_000_000L
    private fun nav() = WearNavigationSummary("session", true, now, false, false, false,
        false, false, 10, "Sağa dön", 150.0, 2200.0, now + 600_000, "Hedef")
    private fun snapshot(advisory: WearGuidanceAdvisory? = WearGuidanceAdvisory("session")) =
        WearSnapshot(now, navigation = nav(), advisory = advisory)
    private fun frame(value: WearSnapshot) = WatchSurfacePolicy.frame(value, true, now)
    private fun text(value: WearSnapshot) = frame(value).lines.joinToString("\n")

    @Test fun pausedGuidanceSuppressesEtaAndTurnWithoutInventingGpsLoss() {
        val value = snapshot(WearGuidanceAdvisory("session", paused = true))
        val surface = frame(value)
        assertEquals("Duraklama sürüyor. Varış tahmini bekliyor.", surface.lines.first())
        assertFalse(surface.lines.drop(1).any { it.contains("Varış ") ||
            it.contains(WatchSurfacePolicy.clock(now + 600_000)) || it.contains("2,2 km") })
        assertFalse(surface.lines.any { it == "GPS konumu bekleniyor" })
        assertFalse(surface.title.contains("150 m"))
        assertNotEquals(WatchSurfacePolicy.clock(now + 600_000), surface.compact)
        assertEquals(surface, WatchSurfacePolicy.complication(value, true, now))
    }

    @Test fun pauseRetainsIndependentGpsAndReportExpiryBoundaries() {
        val value = snapshot(WearGuidanceAdvisory("session", paused = true))
            .copy(navigation = nav().copy(fixAt = now - 10_000))
        val timeline = WatchSurfaceTimeline.segments(value, true, now, frame(value))
        assertEquals(3, timeline.size)
        assertEquals(now + 20_000, timeline[0].end)
        assertEquals("GPS konumu bekleniyor", timeline[1].frame.lines.first())
        assertEquals("Telefon bilgisi güncel değil", timeline[2].frame.lines.first())
    }

    @Test fun realGpsLossOutranksPauseWithoutRestoringEta() {
        val value = snapshot(WearGuidanceAdvisory("session", paused = true))
            .copy(navigation = nav().copy(fixAt = null, gpsStale = true))
        assertEquals("GPS konumu bekleniyor", frame(value).lines.first())
        assertFalse(text(value).contains("Varış "))
    }

    @Test fun absentV6AdvisoryIsUnknownRatherThanReadyOrPaused() {
        val decoded = WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(snapshot(), 6))!!
        assertNull(decoded.advisory)
        assertTrue(text(decoded).contains("Harita alanı durumu bilinmiyor."))
        assertTrue(text(decoded).contains("Ses durumu bilinmiyor."))
        assertEquals("Sağa dön", frame(decoded).lines.first())
    }

    @Test fun mismatchedOwnerCannotPauseOrClaimMapOrVoiceReadiness() {
        val value = snapshot(WearGuidanceAdvisory("other", paused = true, preparedRoute = true,
            map = WearMapAvailability.READY, voice = WearVoiceAvailability.LOCAL_READY, muted = true))
        val lines = text(value)
        assertEquals("Sağa dön", frame(value).lines.first())
        assertTrue(lines.contains("Harita alanı durumu bilinmiyor."))
        assertTrue(lines.contains("Ses durumu bilinmiyor."))
        assertFalse(lines.contains("Navigasyon sesi kapalı") || lines.contains("harita alanı hazır"))
    }

    @Test fun preparedRouteWithoutGuidanceStillHasAnInformationalSurface() {
        val value = snapshot(WearGuidanceAdvisory("session", preparedRoute = true, map = WearMapAvailability.NO_PACK))
            .copy(navigation = nav().copy(guidance = false, fixAt = null, arrivalAt = null))
        val surface = frame(value)
        assertEquals(WatchSurfaceRoute.NAVIGATION, surface.route)
        assertEquals("Rota hazır.", surface.lines.first())
        assertTrue(text(value).contains("Rota hazır. Harita alanı hazır değil."))
        assertFalse(text(value).contains("GPS konumu bekleniyor"))
    }

    @Test fun eachMapStateKeepsRouteAndCoverageReadinessSeparate() {
        val copy = mapOf(
            WearMapAvailability.UNKNOWN to "Harita alanı durumu bilinmiyor.",
            WearMapAvailability.NO_PACK to "Rota hazır. Harita alanı hazır değil.",
            WearMapAvailability.PARTIAL to "Harita yalnızca kaydedilmiş alanda görünür.",
            WearMapAvailability.READY to "Rota ve seçili harita alanı hazır.",
            WearMapAvailability.OUTSIDE to "Bu bölüm kaydedilmiş harita alanının dışında.")
        for ((state, expected) in copy) {
            assertTrue(state.name, text(snapshot(WearGuidanceAdvisory("session", preparedRoute = true, map = state))).contains(expected))
        }
        val mapOnly = text(snapshot(WearGuidanceAdvisory("session", map = WearMapAvailability.READY)))
        assertTrue(mapOnly.contains("Seçili harita alanı hazır."))
        assertFalse(mapOnly.contains("Rota ve seçili"))
    }

    @Test fun voiceStatesNeverClaimAudibilityOrIntercomConnection() {
        val copy = mapOf(
            WearVoiceAvailability.UNKNOWN to "Ses durumu bilinmiyor.",
            WearVoiceAvailability.LOCAL_READY to "Türkçe ses motoru hazır. Sesin duyulduğu henüz doğrulanmadı.",
            WearVoiceAvailability.NETWORK_REQUIRED to "Türkçe seslendirme için internet gerekir.",
            WearVoiceAvailability.UNAVAILABLE to "Türkçe seslendirme kullanılamıyor. Görsel yönlendirme sürüyor.",
            WearVoiceAvailability.OUTPUT_FAILED to "Dönüş anonsu duyurulamadı. Görsel yönlendirme sürüyor.")
        for ((state, expected) in copy) {
            val lines = text(snapshot(WearGuidanceAdvisory("session", voice = state)))
            assertTrue(state.name, lines.contains(expected))
            assertFalse(lines.contains("İnterkom bağlı") || lines.contains("Ses duyuldu."))
        }
    }

    @Test fun explicitMuteOutranksVoiceReadiness() {
        val value = snapshot(WearGuidanceAdvisory("session", voice = WearVoiceAvailability.LOCAL_READY, muted = true))
        assertTrue(text(value).contains("Navigasyon sesi kapalı."))
        assertFalse(text(value).contains("ses motoru hazır"))
    }

    @Test fun disconnectedAndExpiredReportsCannotKeepAdvisoryClaimsCurrent() {
        val value = snapshot(WearGuidanceAdvisory("session", paused = true, preparedRoute = true,
            map = WearMapAvailability.READY, voice = WearVoiceAvailability.LOCAL_READY))
        for ((connected, at, expected) in listOf(Triple(false, now, "Telefon bağlı değil"),
            Triple(true, now + 30_000, "Telefon bilgisi güncel değil"))) {
            val surface = WatchSurfacePolicy.frame(value, connected, at)
            assertEquals(expected, surface.lines.first())
            assertFalse(surface.lines.any { it.contains("Duraklama sürüyor") || it.contains("harita alanı hazır") || it.contains("ses motoru hazır") })
        }
    }

    @Test fun unrelatedAdvisoryCannotCreateAPreparedNavigationSurface() {
        val value = snapshot(WearGuidanceAdvisory("other", preparedRoute = true))
            .copy(navigation = nav().copy(guidance = false))
        assertEquals(WatchSurfaceRoute.DAILY, frame(value).route)
    }

    @Test fun deferredStatisticsStillDoNotBecomeZerosWithV7Advisory() {
        val value = snapshot(WearGuidanceAdvisory("session", paused = true)).copy(
            journeyId = "journey", recording = true, statsDeferred = true)
        assertFalse(text(value).contains("0,0 km") || text(value).contains("0:00") || text(value).contains("Adım 0"))
    }
}
