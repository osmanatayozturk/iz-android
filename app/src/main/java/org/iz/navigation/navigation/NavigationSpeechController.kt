package org.iz.navigation.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EngineReadiness { UNCHECKED, INITIALIZING, READY, ENGINE_ERROR, LANGUAGE_MISSING, LANGUAGE_UNSUPPORTED, INIT_TIMEOUT }
enum class SpeechChannel { TURN, WEATHER, TEST }
enum class SpeechFailure { AUDIO_FOCUS, OUTPUT }
enum class GuidanceVoiceMode { DEFAULT, PREPARED_LOCAL }
/** AVAILABLE means a verified selected, installed local Turkish voice, never audibility. */
enum class LocalVoiceCapability { UNKNOWN, AVAILABLE, NETWORK_REQUIRED, MISSING, SELECTION_FAILED }
data class SpeechRequestFailure(val channel: SpeechChannel, val reason: SpeechFailure)
data class SpeechStatus(
    val readiness: EngineReadiness = EngineReadiness.UNCHECKED,
    val requestFailure: SpeechRequestFailure? = null,
    val localVoice: LocalVoiceCapability = LocalVoiceCapability.UNKNOWN,
    val voiceMode: GuidanceVoiceMode = GuidanceVoiceMode.DEFAULT,
)
enum class SpeechTestResult { ACCEPTED, BUSY, UNAVAILABLE }

internal data class SpeechVoice(val name: String, val language: String, val country: String,
    val requiresNetwork: Boolean?, val installed: Boolean?) {
    val turkishInstalled get() = language.equals("tr", ignoreCase = true) && installed == true
    val localTurkish get() = turkishInstalled && requiresNetwork == false
}
internal data class SpeechVoiceCatalog(val voices: List<SpeechVoice>?, val current: SpeechVoice?)
internal interface SpeechEngine {
    fun initialize(onReady: (EngineReadiness) -> Unit, onFinished: (String, Boolean) -> Unit)
    fun voiceCatalog(): SpeechVoiceCatalog? = null
    fun selectVoice(name: String): Boolean = false
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
    private var initializationExpiresAt: Long? = null
    private var outputDeadline: (() -> Unit)? = null
    private var outputExpiresAt: Long? = null
    private var pending: Speech? = null
    private var active: Speech? = null
    private var focus: SpeechFocusLease? = null
    private var failureOwner: SpeechChannel? = null
    private var voiceMode = GuidanceVoiceMode.DEFAULT
    private var defaultVoice: String? = null
    private data class Speech(val id: String, val text: String, val channel: SpeechChannel,
        val expiresAt: Long, val cancelWith: SpeechChannel, val request: NavigationCueRequest?)

    /** Once after an explicit guidance activation commits, never on GPS updates. */
    fun prepareGuidance(mode: GuidanceVoiceMode = GuidanceVoiceMode.DEFAULT) {
        dropPending(CueDelivery.CANCELLED)
        stopActive()
        voiceMode = mode
        failureOwner = null
        mutableStatus.value = mutableStatus.value.copy(requestFailure = null, voiceMode = mode)
        if (status.value.readiness == EngineReadiness.READY) refreshVoice()
        else if (status.value.readiness != EngineReadiness.INITIALIZING) initialize()
    }
    /** An explicit retry never replays an old cue; it retains the current guidance voice policy. */
    fun retry() {
        dropPending(CueDelivery.CANCELLED)
        stopActive()
        releaseEngine()
        initialize()
    }
    fun speakTurn(text: String) { submit(text, SpeechChannel.TURN) }
    fun speakTurn(request: NavigationCueRequest) {
        if (!submit(request.text, SpeechChannel.TURN, request = request)) request.finish(CueDelivery.REJECTED)
    }
    fun speakWeather(text: String) { submit(text, SpeechChannel.WEATHER) }
    fun testVoice(owner: SpeechChannel = SpeechChannel.TURN): SpeechTestResult {
        require(owner == SpeechChannel.TURN || owner == SpeechChannel.WEATHER)
        reconcileDeadlines()
        if (active?.channel == SpeechChannel.TURN || pending?.channel == SpeechChannel.TURN) return SpeechTestResult.BUSY
        return if (submit("İz ses denemesi. Bu bir sesli yönlendirme testidir.", SpeechChannel.TEST, owner))
            SpeechTestResult.ACCEPTED else SpeechTestResult.UNAVAILABLE
    }
    /** Current owner ends synchronously; temporary mute/GPS cancellation uses cancelTurns instead. */
    fun endGuidanceVoice() {
        cancelTurns()
        voiceMode = GuidanceVoiceMode.DEFAULT
        mutableStatus.value = mutableStatus.value.copy(voiceMode = voiceMode)
        // Never initialize/retry, and never change a voice underneath independent weather ownership.
        // If busy, the existing idle dispatch path restores a verified prior selection later.
        if (active == null && pending == null && status.value.readiness == EngineReadiness.READY) refreshVoice()
    }
    fun cancelTurns() = cancel(SpeechChannel.TURN)
    fun cancelWeather() = cancel(SpeechChannel.WEATHER)
    fun suspendAutomaticWeather() = cancel(SpeechChannel.WEATHER, automaticOnly = true)

    private fun submit(text: String, channel: SpeechChannel, cancelWith: SpeechChannel = channel,
        request: NavigationCueRequest? = null): Boolean {
        reconcileDeadlines()
        if (request != null && request.expiresAtMillis <= scheduler.nowMillis()) { request.finish(CueDelivery.EXPIRED); return false }
        if (request != null && !request.isCurrent()) { request.finish(CueDelivery.CANCELLED); return false }
        if (text.isBlank()) return false
        if (channel != SpeechChannel.TURN && (active?.channel == SpeechChannel.TURN || pending?.channel == SpeechChannel.TURN)) return false
        if (channel == SpeechChannel.WEATHER && (active?.channel == SpeechChannel.TEST || pending?.channel == SpeechChannel.TEST)) return false
        if (status.value.readiness !in setOf(EngineReadiness.UNCHECKED, EngineReadiness.INITIALIZING, EngineReadiness.READY)) return false
        stopActive()
        dropPending(CueDelivery.CANCELLED)
        if (status.value.readiness == EngineReadiness.READY) {
            refreshVoice()
            if (voiceMode == GuidanceVoiceMode.PREPARED_LOCAL && status.value.localVoice != LocalVoiceCapability.AVAILABLE) {
                failureOwner = cancelWith
                mutableStatus.value = mutableStatus.value.copy(requestFailure = SpeechRequestFailure(channel, SpeechFailure.OUTPUT))
                return false
            }
        }
        pending = Speech((++sequence).toString(), text.take(500), channel,
            minOf(scheduler.nowMillis() + 15_000L, request?.expiresAtMillis ?: Long.MAX_VALUE), cancelWith, request)
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
        mutableStatus.value = SpeechStatus(EngineReadiness.INITIALIZING, voiceMode = voiceMode)
        try {
            val created = createEngine()
            engine = created
            initializationExpiresAt = scheduler.nowMillis() + 10_000L
            deadline = scheduler.after(10_000L) {
                if (generation == engineGeneration && status.value.readiness == EngineReadiness.INITIALIZING)
                    failEngine(EngineReadiness.INIT_TIMEOUT)
            }
            created.initialize({ readiness ->
                if (generation == engineGeneration && engine === created && status.value.readiness == EngineReadiness.INITIALIZING) {
                    if (initializationExpiresAt?.let { scheduler.nowMillis() >= it } == true) {
                        failEngine(EngineReadiness.INIT_TIMEOUT)
                    } else if (readiness == EngineReadiness.READY) {
                        deadline?.invoke(); deadline = null
                        initializationExpiresAt = null
                        mutableStatus.value = SpeechStatus(EngineReadiness.READY, voiceMode = voiceMode)
                        refreshVoice()
                        drain()
                    } else failEngine(when (readiness) {
                        EngineReadiness.LANGUAGE_MISSING, EngineReadiness.LANGUAGE_UNSUPPORTED -> readiness
                        else -> EngineReadiness.ENGINE_ERROR
                    })
                }
            }, { id, failed ->
                if (generation == engineGeneration && engine === created && active?.id == id) {
                    if (outputExpiresAt?.let { scheduler.nowMillis() >= it } == true)
                        failRequest(SpeechFailure.OUTPUT, CueDelivery.EXPIRED)
                    else if (failed) failRequest(SpeechFailure.OUTPUT) else stopActive(CueDelivery.COMPLETED)
                }
            })
        } catch (_: Exception) { if (generation == engineGeneration) failEngine(EngineReadiness.ENGINE_ERROR) }
    }
    private fun failEngine(reason: EngineReadiness) {
        val test = pending?.channel == SpeechChannel.TEST || active?.channel == SpeechChannel.TEST
        dropPending(pending?.let(::failedDelivery) ?: CueDelivery.REJECTED)
        stopActive(active?.let(::failedDelivery) ?: CueDelivery.REJECTED)
        releaseEngine()
        failureOwner = null
        mutableStatus.value = SpeechStatus(reason, voiceMode = voiceMode, localVoice =
            if (reason == EngineReadiness.LANGUAGE_MISSING || reason == EngineReadiness.LANGUAGE_UNSUPPORTED)
                LocalVoiceCapability.MISSING else LocalVoiceCapability.UNKNOWN)
        if (test) onTestFailure()
    }
    private fun releaseEngine() {
        engineGeneration++
        deadline?.invoke(); deadline = null
        initializationExpiresAt = null
        defaultVoice = null
        val previous = engine
        engine = null
        runCatching { previous?.shutdown() }
    }
    private fun drain() {
        val speech = pending ?: return
        pending = null
        if (speech.expiresAt <= scheduler.nowMillis()) { speech.request?.finish(CueDelivery.EXPIRED); return }
        if (speech.request?.isCurrent() == false) { speech.request.finish(CueDelivery.CANCELLED); return }
        val currentEngine = engine ?: run { speech.request?.finish(CueDelivery.REJECTED); return }
        refreshVoice()
        if (voiceMode == GuidanceVoiceMode.PREPARED_LOCAL && status.value.localVoice != LocalVoiceCapability.AVAILABLE) {
            failureOwner = speech.cancelWith
            mutableStatus.value = mutableStatus.value.copy(requestFailure = SpeechRequestFailure(speech.channel, SpeechFailure.OUTPUT))
            speech.request?.finish(CueDelivery.REJECTED)
            if (speech.channel == SpeechChannel.TEST) onTestFailure()
            return
        }
        val generation = engineGeneration
        active = speech
        val acquired = runCatching { audioFocus.acquire {
            if (generation == engineGeneration && active?.id == speech.id) failRequest(SpeechFailure.AUDIO_FOCUS)
        } }.getOrNull()
        if (active?.id != speech.id || generation != engineGeneration) { acquired?.release(); return }
        if (acquired == null) { failRequest(SpeechFailure.AUDIO_FOCUS); return }
        focus = acquired
        if (speech.expiresAt <= scheduler.nowMillis()) { stopActive(CueDelivery.EXPIRED); return }
        if (speech.request?.isCurrent() == false) { stopActive(); return }
        failureOwner = null
        mutableStatus.value = mutableStatus.value.copy(requestFailure = null)
        // Application fail-safe policy, not an Android guarantee; very slow long speech may be cut short.
        outputExpiresAt = scheduler.nowMillis() + 30_000L
        outputDeadline = scheduler.after(30_000L) {
            if (generation == engineGeneration && active?.id == speech.id) expireActive()
        }
        val accepted = runCatching { currentEngine.speak(speech.text, speech.id) }.getOrDefault(false)
        if (!accepted && active?.id == speech.id && generation == engineGeneration) failRequest(SpeechFailure.OUTPUT)
    }
    private fun reconcileDeadlines() {
        expireActive()
        if (status.value.readiness == EngineReadiness.INITIALIZING &&
            initializationExpiresAt?.let { scheduler.nowMillis() >= it } == true) {
            failEngine(EngineReadiness.INIT_TIMEOUT)
        } else {
            val queued = pending
            if (queued != null && queued.expiresAt <= scheduler.nowMillis()) dropPending(CueDelivery.EXPIRED)
            else if (queued?.request?.isCurrent() == false) dropPending(CueDelivery.CANCELLED)
        }
    }
    private fun expireActive() {
        if (active != null && outputExpiresAt?.let { scheduler.nowMillis() >= it } == true)
            failRequest(SpeechFailure.OUTPUT, CueDelivery.EXPIRED)
    }
    private fun failedDelivery(speech: Speech): CueDelivery = when {
        speech.expiresAt <= scheduler.nowMillis() || outputExpiresAt?.let { scheduler.nowMillis() >= it } == true -> CueDelivery.EXPIRED
        speech.request?.isCurrent() == false -> CueDelivery.CANCELLED
        else -> CueDelivery.REJECTED
    }
    private fun failRequest(reason: SpeechFailure, result: CueDelivery? = null) {
        val speech = active ?: return
        failureOwner = speech.cancelWith
        mutableStatus.value = mutableStatus.value.copy(requestFailure = SpeechRequestFailure(speech.channel, reason))
        stopActive(result ?: failedDelivery(speech))
        if (speech.channel == SpeechChannel.TEST) onTestFailure()
    }
    private fun dropPending(result: CueDelivery) {
        val previous = pending
        pending = null
        previous?.request?.finish(result)
    }
    private fun cancel(channel: SpeechChannel, automaticOnly: Boolean = false) {
        fun Speech.belongs() = if (automaticOnly) this.channel == channel else cancelWith == channel
        if (pending?.belongs() == true) dropPending(CueDelivery.CANCELLED)
        if (active?.belongs() == true) stopActive()
        if (failureOwner == channel && (!automaticOnly || status.value.requestFailure?.channel == channel)) {
            failureOwner = null
            mutableStatus.value = mutableStatus.value.copy(requestFailure = null)
        }
    }
    private fun stopActive(result: CueDelivery = CueDelivery.CANCELLED) {
        val previousSpeech = active
        active = null
        outputExpiresAt = null
        outputDeadline?.invoke(); outputDeadline = null
        val previous = focus
        focus = null
        if (previousSpeech != null) runCatching { engine?.stop() }
        runCatching { previous?.release() }
        previousSpeech?.request?.finish(result)
    }

    private fun catalog() = runCatching { engine?.voiceCatalog() }.getOrNull()
    private fun refreshVoice() {
        val currentEngine = engine ?: return
        var snapshot = catalog()
        if (voiceMode == GuidanceVoiceMode.DEFAULT && defaultVoice != null) {
            val restore = snapshot?.voices?.firstOrNull { it.name == defaultVoice && it.turkishInstalled }
            if (restore != null) {
                val restored = runCatching { currentEngine.selectVoice(restore.name) }.getOrDefault(false)
                snapshot = catalog()
                if (restored && snapshot?.current?.name == restore.name && snapshot?.current?.turkishInstalled == true)
                    defaultVoice = null
            }
        }
        if (voiceMode == GuidanceVoiceMode.PREPARED_LOCAL && snapshot?.current?.localTurkish != true) {
            val local = snapshot?.voices?.filter { it.localTurkish }
                ?.sortedWith(compareBy<SpeechVoice> { !it.country.equals("TR", ignoreCase = true) }.thenBy { it.name })?.firstOrNull()
            if (local != null) {
                val prior = snapshot?.current
                if (defaultVoice == null && prior?.turkishInstalled == true) defaultVoice = prior.name
                val selected = runCatching { currentEngine.selectVoice(local.name) }.getOrDefault(false)
                snapshot = catalog()
                val actual = snapshot?.current
                if (!selected || actual?.name != local.name || actual?.localTurkish != true) {
                    mutableStatus.value = mutableStatus.value.copy(localVoice = LocalVoiceCapability.SELECTION_FAILED)
                    return
                }
            }
        }
        val selected = snapshot?.current
        val voices = snapshot?.voices
        val capability = when {
            selected?.localTurkish == true -> LocalVoiceCapability.AVAILABLE
            selected?.turkishInstalled == true && selected.requiresNetwork == true -> LocalVoiceCapability.NETWORK_REQUIRED
            voices == null -> LocalVoiceCapability.UNKNOWN
            voices.any { it.language.equals("tr", ignoreCase = true) &&
                (it.installed == null || it.requiresNetwork == null) } -> LocalVoiceCapability.UNKNOWN
            voices.any { it.turkishInstalled && it.requiresNetwork == true } -> LocalVoiceCapability.NETWORK_REQUIRED
            voices.any { it.localTurkish } -> LocalVoiceCapability.SELECTION_FAILED
            else -> LocalVoiceCapability.MISSING
        }
        mutableStatus.value = mutableStatus.value.copy(localVoice = capability)
    }
}
