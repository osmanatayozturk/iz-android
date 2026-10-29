package org.iz.navigation.ui

import org.iz.navigation.navigation.*
import org.junit.Assert.*
import org.junit.Test

class PhoneSpeechPresentationTest {
    @Test fun mutedOrInactiveGuidanceNeverTurnsIntentionalSilenceIntoVoiceFailure() {
        val failed = SpeechStatus(EngineReadiness.LANGUAGE_MISSING)
        assertNull(phoneVoiceFault(failed, guidance = false, muted = false))
        assertNull(phoneVoiceFault(failed, guidance = true, muted = true))
        assertNotNull(phoneVoiceFault(failed, guidance = true, muted = false))
    }
    @Test fun weatherOrTestRequestFailureDoesNotMasqueradeAsTurnFailure() {
        for (channel in listOf(SpeechChannel.WEATHER, SpeechChannel.TEST)) {
            val status = SpeechStatus(EngineReadiness.READY, SpeechRequestFailure(channel, SpeechFailure.OUTPUT))
            assertNull(phoneVoiceFault(status, true, false))
            assertNotNull(phoneVoicePresentation(status).requestFailure)
        }
        assertNotNull(phoneVoiceFault(SpeechStatus(EngineReadiness.READY,
            SpeechRequestFailure(SpeechChannel.TURN, SpeechFailure.AUDIO_FOCUS)), true, false))
    }
    @Test fun uncheckedInitializingAndReadyAreNotFaultsOrAudibilityProof() {
        for (readiness in listOf(EngineReadiness.UNCHECKED, EngineReadiness.INITIALIZING, EngineReadiness.READY))
            assertNull(phoneVoiceFault(SpeechStatus(readiness), true, false))
        assertTrue(phoneVoicePresentation(SpeechStatus(EngineReadiness.READY)).readiness.contains("duyulduğu henüz doğrulanmadı"))
    }
    @Test fun engineReasonsStayDistinctFromRequestOutputAndFocusReasons() {
        val engine = EngineReadiness.entries.associateWith { phoneVoicePresentation(SpeechStatus(it)).readiness }
        assertEquals(EngineReadiness.entries.size, engine.values.toSet().size)
        val focus = phoneVoicePresentation(SpeechStatus(EngineReadiness.READY,
            SpeechRequestFailure(SpeechChannel.TURN, SpeechFailure.AUDIO_FOCUS)))
        val output = phoneVoicePresentation(SpeechStatus(EngineReadiness.READY,
            SpeechRequestFailure(SpeechChannel.TURN, SpeechFailure.OUTPUT)))
        assertNotEquals(focus.requestFailure, output.requestFailure)
        assertEquals(focus.readiness, output.readiness)
    }
}
