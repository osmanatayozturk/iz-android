package org.iz.navigation.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EngineReadiness { UNCHECKED, INITIALIZING, READY, ENGINE_ERROR, LANGUAGE_MISSING, LANGUAGE_UNSUPPORTED, INIT_TIMEOUT }
enum class SpeechChannel { TURN, WEATHER, TEST }
enum class SpeechFailure { AUDIO_FOCUS, OUTPUT }
data class SpeechRequestFailure(val channel: SpeechChannel, val reason: SpeechFailure)
data class SpeechStatus(val readiness: EngineReadiness = EngineReadiness.UNCHECKED, val requestFailure: SpeechRequestFailure? = null)
enum class SpeechTestResult { ACCEPTED, BUSY, UNAVAILABLE }

internal interface SpeechEngine {
    fun initialize(onReady: (EngineReadiness) -> Unit, onFinished: (String, Boolean) -> Unit)
    fun speak(text: String, id: String): Boolean
    fun stop()
    fun shutdown()
}
internal fun interface SpeechFocusLease { fun release() }
internal fun interface SpeechAudioFocus { fun acquire(onLost: () -> Unit): SpeechFocusLease? }
internal interface SpeechScheduler {
    fun nowMillis(): Long
    fun after(delayMillis: Long, task: () -> Unit): () -> Unit
}

/** Serialized by the Android owner on its main thread. Readiness never means audible output. */
internal class NavigationSpeechController(
    private val createEngine: () -> SpeechEngine,
    private val audioFocus: SpeechAudioFocus,
    private val scheduler: SpeechScheduler,
    private val onTestFailure: () -> Unit = {},
) {
    private val mutableStatus = MutableStateFlow(SpeechStatus())
    val status: StateFlow<SpeechStatus> = mutableStatus.asStateFlow()
    private var engine: SpeechEngine? = null
    private var engineGeneration = 0L
    private var sequence = 0L
    private var deadline: (() -> Unit)? = null
    private var pending: Speech? = null
    private var active: Speech? = null
    private var focus: SpeechFocusLease? = null
    private var failureOwner: SpeechChannel? = null
    private data class Speech(val id: String, val text: String, val channel: SpeechChannel, val expiresAt: Long, val cancelWith: SpeechChannel)

    /** The caller invokes this once after a new guidance activation commits, never on GPS updates. */
    fun prepareGuidance() {
        pending = null
        stopActive()
        failureOwner = null
        mutableStatus.value = mutableStatus.value.copy(requestFailure = null)
        if (status.value.readiness !in setOf(EngineReadiness.READY, EngineReadiness.INITIALIZING)) initialize()
    }
    /** An explicit retry (including return from explicitly opened settings) never replays an old cue. */
    fun retry() {
        pending = null
        stopActive()
        releaseEngine()
        initialize()
    }
    fun speakTurn(text: String) { submit(text, SpeechChannel.TURN) }
    fun speakWeather(text: String) { submit(text, SpeechChannel.WEATHER) }
    fun testVoice(owner: SpeechChannel = SpeechChannel.TURN): SpeechTestResult {
        require(owner == SpeechChannel.TURN || owner == SpeechChannel.WEATHER)
        if (active?.channel == SpeechChannel.TURN || pending?.channel == SpeechChannel.TURN) return SpeechTestResult.BUSY
        return if (submit("İz ses denemesi. Bu bir sesli yönlendirme testidir.", SpeechChannel.TEST, owner))
            SpeechTestResult.ACCEPTED else SpeechTestResult.UNAVAILABLE
    }
    fun cancelTurns() = cancel(SpeechChannel.TURN)
    fun cancelWeather() = cancel(SpeechChannel.WEATHER)
    fun suspendAutomaticWeather() = cancel(SpeechChannel.WEATHER, automaticOnly = true)

    private fun submit(text: String, channel: SpeechChannel, cancelWith: SpeechChannel = channel): Boolean {
        if (text.isBlank()) return false
        if (channel != SpeechChannel.TURN && (active?.channel == SpeechChannel.TURN || pending?.channel == SpeechChannel.TURN)) return false
        if (channel == SpeechChannel.WEATHER && (active?.channel == SpeechChannel.TEST || pending?.channel == SpeechChannel.TEST)) return false
        // A failed engine stays failed until a fresh committed session or an explicit retry.
        if (status.value.readiness !in setOf(EngineReadiness.UNCHECKED, EngineReadiness.INITIALIZING, EngineReadiness.READY)) return false
        stopActive()
        pending = Speech((++sequence).toString(), text.take(500), channel, scheduler.nowMillis() + 15_000L, cancelWith)
        when (status.value.readiness) {
            EngineReadiness.READY -> drain()
            EngineReadiness.UNCHECKED -> initialize()
            else -> Unit
        }
        return true
    }

    private fun initialize() {
        releaseEngine()
        val generation = engineGeneration
        failureOwner = null
        mutableStatus.value = SpeechStatus(EngineReadiness.INITIALIZING)
        try {
            val created = createEngine()
            engine = created
            deadline = scheduler.after(10_000L) {
                if (generation == engineGeneration && status.value.readiness == EngineReadiness.INITIALIZING)
                    failEngine(EngineReadiness.INIT_TIMEOUT)
            }
            created.initialize({ readiness ->
                if (generation == engineGeneration && engine === created && status.value.readiness == EngineReadiness.INITIALIZING) {
                    if (readiness == EngineReadiness.READY) {
                        deadline?.invoke(); deadline = null
                        mutableStatus.value = SpeechStatus(EngineReadiness.READY)
                        drain()
                    } else failEngine(when (readiness) {
                        EngineReadiness.LANGUAGE_MISSING, EngineReadiness.LANGUAGE_UNSUPPORTED -> readiness
                        else -> EngineReadiness.ENGINE_ERROR
                    })
                }
            }, { id, failed ->
                if (generation == engineGeneration && engine === created && active?.id == id) {
                    if (failed) failRequest(SpeechFailure.OUTPUT) else stopActive()
                }
            })
        } catch (_: Exception) { if (generation == engineGeneration) failEngine(EngineReadiness.ENGINE_ERROR) }
    }

    private fun failEngine(reason: EngineReadiness) {
        val test = pending?.channel == SpeechChannel.TEST || active?.channel == SpeechChannel.TEST
        pending = null
        stopActive()
        releaseEngine()
        failureOwner = null
        mutableStatus.value = SpeechStatus(reason)
        if (test) onTestFailure()
    }
    private fun releaseEngine() {
        engineGeneration++
        deadline?.invoke(); deadline = null
        val previous = engine
        engine = null
        runCatching { previous?.shutdown() }
    }
    private fun drain() {
        val speech = pending ?: return
        pending = null
        if (speech.expiresAt <= scheduler.nowMillis()) return
        val currentEngine = engine ?: return
        val generation = engineGeneration
        active = speech
        val acquired = runCatching { audioFocus.acquire {
            if (generation == engineGeneration && active?.id == speech.id) failRequest(SpeechFailure.AUDIO_FOCUS)
        } }.getOrNull()
        // A platform/fake can report loss synchronously during focus acquisition.
        if (active?.id != speech.id || generation != engineGeneration) { acquired?.release(); return }
        if (acquired == null) { failRequest(SpeechFailure.AUDIO_FOCUS); return }
        focus = acquired
        failureOwner = null
        mutableStatus.value = mutableStatus.value.copy(requestFailure = null)
        val accepted = runCatching { currentEngine.speak(speech.text, speech.id) }.getOrDefault(false)
        if (!accepted && active?.id == speech.id && generation == engineGeneration) failRequest(SpeechFailure.OUTPUT)
    }
    private fun failRequest(reason: SpeechFailure) {
        val speech = active ?: return
        failureOwner = speech.cancelWith
        mutableStatus.value = mutableStatus.value.copy(requestFailure = SpeechRequestFailure(speech.channel, reason))
        stopActive()
        if (speech.channel == SpeechChannel.TEST) onTestFailure()
    }
    private fun cancel(channel: SpeechChannel, automaticOnly: Boolean = false) {
        fun Speech.belongs() = if (automaticOnly) this.channel == channel else cancelWith == channel
        if (pending?.belongs() == true) pending = null
        if (active?.belongs() == true) stopActive()
        if (failureOwner == channel && (!automaticOnly || status.value.requestFailure?.channel == channel)) {
            failureOwner = null
            mutableStatus.value = mutableStatus.value.copy(requestFailure = null)
        }
    }
    private fun stopActive() {
        val speaking = active != null
        active = null
        val previous = focus
        focus = null
        if (speaking) runCatching { engine?.stop() }
        runCatching { previous?.release() }
    }
}
