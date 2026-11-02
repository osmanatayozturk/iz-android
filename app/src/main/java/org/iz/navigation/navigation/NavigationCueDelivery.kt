package org.iz.navigation.navigation

/** Engine completion is delivery evidence, never proof that the rider heard the cue. */
enum class CueDelivery { COMPLETED, REJECTED, CANCELLED, EXPIRED }

/** Serialized on the speech owner. A retry retains its first offer's monotonic deadline. */
class NavigationCueRequest(
    val text: String,
    val expiresAtMillis: Long = Long.MAX_VALUE,
    private val stillCurrent: (NavigationCueRequest) -> Boolean = { true },
    private val onFinished: (CueDelivery) -> Unit,
) {
    private var finished = false
    fun isCurrent(): Boolean = !finished && runCatching { stillCurrent(this) }.getOrDefault(false)
    fun finish(result: CueDelivery) {
        if (finished) return
        finished = true
        onFinished(result)
    }
}

interface NavigationCueRuntime {
    fun speakCue(request: NavigationCueRequest)
}

/** Prepared start must observe the actual owner status, independently from map/route readiness. */
interface NavigationVoiceRuntime {
    fun prepareSpeech(mode: GuidanceVoiceMode)
    val speechStatus: kotlinx.coroutines.flow.StateFlow<SpeechStatus>
}
