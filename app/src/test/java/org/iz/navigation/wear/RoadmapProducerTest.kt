package org.iz.navigation.wear

import org.iz.navigation.data.Transport
import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackFollowProgress
import org.iz.navigation.gpx.TrackFollowSelection
import org.iz.navigation.gpx.TrackFollowState
import org.iz.navigation.gpx.TrackFollowStatus
import org.iz.navigation.navigation.*
import org.iz.navigation.weather.*
import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test

class RoadmapProducerTest {
    @Test fun pausedSummarySuppressesManeuverAndEtaWithoutInventingGpsFailure() {
        val paused = state().copy(paused = true)
        val summary = requireNotNull(WearNavigationFactory.create(paused, NOW))
        assertEquals("producer-session", summary.sessionId)
        assertTrue(summary.guidance)
        assertFalse(summary.gpsStale)
        assertEquals(999_000L, summary.fixAt)
        assertNull(summary.maneuverType)
        assertNull(summary.nextManeuverMeters)
        assertNull(summary.remainingMeters)
        assertNull(summary.arrivalAt)
        assertNotEquals("Turn left", summary.instruction)
    }

    @Test fun legacyProgressEtaAndGpsAgeRemainStableWhenTransportRepublishes() {
        val original = state()
        val first = requireNotNull(WearNavigationFactory.create(original, NOW))
        val later = requireNotNull(WearNavigationFactory.create(original, NOW + 5_000))
        // No event timestamp means legacy fixAt999000 + remaining90000, not publication time.
        assertEquals(1_089_000L, first.arrivalAt)
        assertEquals(1_089_000L, later.arrivalAt)
        assertEquals(999_000L, first.fixAt)
        assertEquals(999_000L, later.fixAt)
        assertEquals(15, later.maneuverType)
        assertEquals(120.0, later.nextManeuverMeters!!, 0.0)
        assertFalse(later.gpsStale)
    }

    @Test fun actualResumeEventRebasesEtaOnceAndLaterPublicationKeepsItStable() {
        val paused = requireNotNull(WearNavigationFactory.create(state().copy(paused = true), NOW))
        assertNull(paused.arrivalAt)
        // Explicit resume at1005000 uses the still-fresh original GPS fix; no fabricated new fix.
        val resumedState = state().copy(paused = false, progressUpdatedAt = 1_005_000)
        val resumed = requireNotNull(WearNavigationFactory.create(resumedState, 1_005_000))
        val republished = requireNotNull(WearNavigationFactory.create(resumedState, 1_010_000))
        assertEquals(1_095_000L, resumed.arrivalAt)
        assertEquals(1_095_000L, republished.arrivalAt)
        assertEquals(999_000L, resumed.fixAt)
        assertEquals(999_000L, republished.fixAt)
        assertEquals(15, resumed.maneuverType)
        assertFalse(resumed.gpsStale)
    }

    @Test fun progressEventOutsideFixToNowRangeCannotRebaseTheSummary() {
        listOf(998_999L, 1_000_001L).forEach { invalidAnchor ->
            val summary = requireNotNull(WearNavigationFactory.create(state().copy(progressUpdatedAt = invalidAnchor), NOW))
            assertEquals(1_089_000L, summary.arrivalAt)
            assertEquals(999_000L, summary.fixAt)
            assertFalse(summary.gpsStale)
        }
    }

    @Test fun staleGpsDoesNotBecomeAnExplicitUserPauseAndCannotRetainEta() {
        val original = state().copy(progressUpdatedAt = NOW + 28_000)
        val summary = requireNotNull(WearNavigationFactory.create(original, NOW + 29_000))
        assertTrue(summary.gpsStale)
        assertEquals(999_000L, summary.fixAt)
        assertNull(summary.arrivalAt)
        assertNull(summary.maneuverType)
        val advisory = requireNotNull(WearGuidanceAdvisoryFactory.create(original.copy(gpsStale = true),
            readyVoice(), WearMapAvailability.UNKNOWN))
        assertFalse(advisory.paused)
        assertEquals(WearMapAvailability.UNKNOWN, advisory.map)
    }

    @Test fun pausedPreparedAdvisoryKeepsMapVoiceAndMuteAsSeparateTypedFacts() {
        val advisory = requireNotNull(WearGuidanceAdvisoryFactory.create(state().copy(paused = true,
            preparedRouteFingerprint = FINGERPRINT, muted = true), readyVoice(), WearMapAvailability.PARTIAL))
        assertEquals("producer-session", advisory.sessionId)
        assertTrue(advisory.paused)
        assertTrue(advisory.preparedRoute)
        assertTrue(advisory.muted)
        assertEquals(WearMapAvailability.PARTIAL, advisory.map)
        assertEquals(WearVoiceAvailability.LOCAL_READY, advisory.voice)
    }

    @Test fun preparedRouteDoesNotImplyACompleteMapOrVerifiedLocalVoice() {
        val advisory = requireNotNull(WearGuidanceAdvisoryFactory.create(state().copy(
            preparedRouteFingerprint = FINGERPRINT), readyVoice().copy(localVoice = LocalVoiceCapability.NETWORK_REQUIRED),
            WearMapAvailability.NO_PACK))
        assertTrue(advisory.preparedRoute)
        assertFalse(advisory.paused)
        assertEquals(WearMapAvailability.NO_PACK, advisory.map)
        assertEquals(WearVoiceAvailability.NETWORK_REQUIRED, advisory.voice)
    }

    @Test fun turnOutputFailureOutranksEngineAndLocalSelectionReadiness() {
        listOf(SpeechFailure.OUTPUT, SpeechFailure.AUDIO_FOCUS).forEach { reason ->
            val speech = readyVoice().copy(requestFailure = SpeechRequestFailure(SpeechChannel.TURN, reason))
            val advisory = requireNotNull(WearGuidanceAdvisoryFactory.create(state(), speech, WearMapAvailability.READY))
            assertEquals(reason.name, WearVoiceAvailability.OUTPUT_FAILED, advisory.voice)
            assertEquals(WearMapAvailability.READY, advisory.map)
            assertFalse(advisory.paused)
        }
    }

    @Test fun unrelatedWeatherOrTestFailureDoesNotFabricateATurnOutputFailure() {
        listOf(SpeechChannel.WEATHER, SpeechChannel.TEST).forEach { channel ->
            val speech = readyVoice().copy(requestFailure = SpeechRequestFailure(channel, SpeechFailure.AUDIO_FOCUS))
            val advisory = requireNotNull(WearGuidanceAdvisoryFactory.create(state(), speech, WearMapAvailability.READY))
            assertEquals(channel.name, WearVoiceAvailability.LOCAL_READY, advisory.voice)
        }
    }

    @Test fun unavailableOrInitializingEngineCannotInheritCachedLocalReadyStatus() {
        val cases = listOf(
            EngineReadiness.INITIALIZING to WearVoiceAvailability.UNKNOWN,
            EngineReadiness.UNCHECKED to WearVoiceAvailability.UNKNOWN,
            EngineReadiness.ENGINE_ERROR to WearVoiceAvailability.UNAVAILABLE,
            EngineReadiness.LANGUAGE_MISSING to WearVoiceAvailability.UNAVAILABLE,
            EngineReadiness.LANGUAGE_UNSUPPORTED to WearVoiceAvailability.UNAVAILABLE,
            EngineReadiness.INIT_TIMEOUT to WearVoiceAvailability.UNAVAILABLE,
        )
        cases.forEach { (readiness, expected) ->
            val advisory = requireNotNull(WearGuidanceAdvisoryFactory.create(state(),
                readyVoice().copy(readiness = readiness), WearMapAvailability.READY))
            assertEquals(readiness.name, expected, advisory.voice)
        }
    }

    @Test fun selectedVoiceCapabilityPreservesUnknownNetworkAndFailureStates() {
        val cases = listOf(
            LocalVoiceCapability.AVAILABLE to WearVoiceAvailability.LOCAL_READY,
            LocalVoiceCapability.UNKNOWN to WearVoiceAvailability.UNKNOWN,
            LocalVoiceCapability.NETWORK_REQUIRED to WearVoiceAvailability.NETWORK_REQUIRED,
            LocalVoiceCapability.MISSING to WearVoiceAvailability.UNAVAILABLE,
            LocalVoiceCapability.SELECTION_FAILED to WearVoiceAvailability.UNAVAILABLE,
        )
        cases.forEach { (capability, expected) ->
            val advisory = requireNotNull(WearGuidanceAdvisoryFactory.create(state(),
                readyVoice().copy(localVoice = capability), WearMapAvailability.OUTSIDE))
            assertEquals(capability.name, expected, advisory.voice)
            assertEquals(WearMapAvailability.OUTSIDE, advisory.map)
        }
    }

    @Test fun missingOwnerInactiveAndTrackFollowingCannotLeakAnOldAdvisory() {
        assertNull(WearGuidanceAdvisoryFactory.create(NavigationState(), readyVoice(), WearMapAvailability.READY))
        assertNull(WearGuidanceAdvisoryFactory.create(state().copy(guidance = false), readyVoice(), WearMapAvailability.READY))
        val following = TrackFollowState(ImportedTrack(name = "synthetic", segments = emptyList()),
            TrackFollowSelection(), TrackFollowProgress(0.0, 100.0, null, TrackFollowStatus.WAITING_FOR_GPS))
        val followed = state().copy(trackFollow = following)
        assertNull(WearGuidanceAdvisoryFactory.create(followed, readyVoice(), WearMapAvailability.READY))
        assertNull(WearNavigationFactory.create(followed, NOW))
    }

    private fun readyVoice() = SpeechStatus(readiness = EngineReadiness.READY,
        localVoice = LocalVoiceCapability.AVAILABLE, voiceMode = GuidanceVoiceMode.PREPARED_LOCAL)

    private fun state() = NavigationState(sessionId = "producer-session", guidance = true, recording = false,
        sessionTransport = Transport.MOTORCYCLE, route = route(), gpsStale = false,
        fix = NavigationFix(WeatherCoordinate(0.0, 0.001), 999_000, 5f),
        progress = NavigationProgress(10.0, 90.0, 1_000.0, 0.0, 0, 120.0))

    private fun route() = PlannedRoute("producer-route",
        listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, 0.01))),
        listOf(RouteVertex(WeatherCoordinate(0.0, 0.0), 0.0), RouteVertex(WeatherCoordinate(0.0, 0.01), 100.0)),
        1_111.95, 100.0, 900_000, transport = Transport.MOTORCYCLE,
        maneuvers = listOf(RouteManeuver(15, "Turn left", "Turn left", emptyList(), 1, 1, 100.0, 100.0)))

    private companion object {
        const val NOW = 1_000_000L
        val FINGERPRINT = "a".repeat(64)
    }
}
