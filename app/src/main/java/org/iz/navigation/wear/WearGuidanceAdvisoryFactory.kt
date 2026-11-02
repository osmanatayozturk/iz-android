package org.iz.navigation.wear

import org.iz.navigation.navigation.*
import org.iz.navigation.wearprotocol.*

/** Typed facts from the phone owner only; local selection is not audible-output proof. */
internal object WearGuidanceAdvisoryFactory {
    fun create(state: NavigationState, speech: SpeechStatus, map: WearMapAvailability): WearGuidanceAdvisory? {
        if (state.trackFollow != null || WearNavigationFactory.create(state, state.fix?.recordedAt ?: 0) == null) return null
        val session = state.sessionId ?: return null
        val voice = when {
            speech.requestFailure?.channel == SpeechChannel.TURN -> WearVoiceAvailability.OUTPUT_FAILED
            speech.readiness in setOf(EngineReadiness.ENGINE_ERROR, EngineReadiness.INIT_TIMEOUT,
                EngineReadiness.LANGUAGE_MISSING, EngineReadiness.LANGUAGE_UNSUPPORTED) -> WearVoiceAvailability.UNAVAILABLE
            speech.readiness != EngineReadiness.READY -> WearVoiceAvailability.UNKNOWN
            speech.localVoice == LocalVoiceCapability.AVAILABLE -> WearVoiceAvailability.LOCAL_READY
            speech.localVoice == LocalVoiceCapability.NETWORK_REQUIRED -> WearVoiceAvailability.NETWORK_REQUIRED
            speech.localVoice in setOf(LocalVoiceCapability.MISSING, LocalVoiceCapability.SELECTION_FAILED) -> WearVoiceAvailability.UNAVAILABLE
            else -> WearVoiceAvailability.UNKNOWN
        }
        return WearGuidanceAdvisory(session, state.paused, state.preparedRouteFingerprint != null,
            map, voice, state.muted)
    }
}
