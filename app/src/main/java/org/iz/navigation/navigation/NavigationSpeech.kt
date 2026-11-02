package org.iz.navigation.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** One process-wide Turkish voice and audio-focus owner shared by turns, weather and explicit tests. */
class NavigationSpeech private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val controller = NavigationSpeechController(::AndroidSpeechEngine, SpeechAudioFocus { onLost ->
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change -> if (change < 0) onLost() }, main).build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) null
        else object : SpeechFocusLease {
            private var released = false
            override fun release() {
                if (!released) { released = true; audio.abandonAudioFocusRequest(request) }
            }
        }
    }, object : SpeechScheduler {
        override fun nowMillis() = SystemClock.elapsedRealtime()
        override fun after(delayMillis: Long, task: () -> Unit): () -> Unit {
            val runnable = Runnable { task() }
            main.postDelayed(runnable, delayMillis)
            return { main.removeCallbacks(runnable) }
        }
    }, onTestFailure = {
        Toast.makeText(app, "Ses denemesi tamamlanamadı. Ses durumunu ve telefonun seslendirme ayarlarını kontrol et.", Toast.LENGTH_LONG).show()
    })
    val status: StateFlow<SpeechStatus> = controller.status
    fun prepareGuidance(mode: GuidanceVoiceMode = GuidanceVoiceMode.DEFAULT) = post { controller.prepareGuidance(mode) }
    fun retry() = post { controller.retry() }
    /** Coordinator verifies the ending owner and invokes on the serialized main owner. */
    fun endGuidanceVoice() = post { controller.endGuidanceVoice() }
    /** UI callers are on main; background weather callers use the posted compatible wrapper below. */
    fun testVoice(): SpeechTestResult {
        check(Looper.myLooper() == Looper.getMainLooper())
        return controller.testVoice()
    }
    fun speakTurn(text: String) = post { controller.speakTurn(text) }
    fun speakTurn(request: NavigationCueRequest) = post { controller.speakTurn(request) }
    fun speakWeather(text: String) = post { controller.speakWeather(text) }
    fun testWeather() = post {
        when (controller.testVoice(SpeechChannel.WEATHER)) {
            SpeechTestResult.BUSY -> Toast.makeText(app, "Dönüş anonsu sürüyor. Ses denemesini sonra yeniden başlat.", Toast.LENGTH_LONG).show()
            SpeechTestResult.UNAVAILABLE -> Toast.makeText(app, "Seslendirme kullanılamıyor. Seslendirme ayarlarını kontrol et.", Toast.LENGTH_LONG).show()
            SpeechTestResult.ACCEPTED -> Unit
        }
    }
    fun cancelTurns() = post { controller.cancelTurns() }
    fun cancelWeather() = post { controller.cancelWeather() }
    internal fun suspendAutomaticWeather() = post { controller.suspendAutomaticWeather() }
    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private inner class AndroidSpeechEngine : SpeechEngine {
        private var voice: TextToSpeech? = null
        private var closed = false
        override fun initialize(onReady: (EngineReadiness) -> Unit, onFinished: (String, Boolean) -> Unit) {
            // Always post even if a vendor calls onInit inline, so assignment completes first.
            voice = TextToSpeech(app) { result -> main.post {
                if (closed) return@post
                val engine = voice ?: return@post
                val readiness = runCatching {
                    if (result != TextToSpeech.SUCCESS) EngineReadiness.ENGINE_ERROR
                    else {
                        // setLanguage/setVoice may trigger data download for notInstalled voices.
                        // Preserve a valid selected Turkish voice; select only catalog-proven installed data.
                        val catalog = voiceCatalog()
                        if (catalog?.current?.turkishInstalled == true) EngineReadiness.READY
                        else {
                            val available = catalog?.voices?.filter { it.turkishInstalled }
                                ?.sortedWith(compareBy<SpeechVoice> { it.requiresNetwork != false }.thenBy { it.name })?.firstOrNull()
                            if (available != null) {
                                if (selectVoice(available.name) && voiceCatalog()?.current?.turkishInstalled == true)
                                    EngineReadiness.READY else EngineReadiness.ENGINE_ERROR
                            } else when (engine.isLanguageAvailable(Locale.forLanguageTag("tr-TR"))) {
                                TextToSpeech.LANG_MISSING_DATA -> EngineReadiness.LANGUAGE_MISSING
                                TextToSpeech.LANG_NOT_SUPPORTED -> EngineReadiness.LANGUAGE_UNSUPPORTED
                                else -> if (catalog?.voices == null) EngineReadiness.ENGINE_ERROR else EngineReadiness.LANGUAGE_MISSING
                            }
                        }
                    }
                }.getOrDefault(EngineReadiness.ENGINE_ERROR)
                if (readiness != EngineReadiness.READY) { onReady(readiness); return@post }
                val configured = runCatching {
                    engine.setAudioAttributes(attributes) == TextToSpeech.SUCCESS &&
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) = Unit
                            override fun onDone(id: String?) = finish(id, false)
                            override fun onStop(id: String?, interrupted: Boolean) = finish(id, true)
                            @Deprecated("Android legacy callback")
                            override fun onError(id: String?) = finish(id, true)
                            override fun onError(id: String?, errorCode: Int) = finish(id, true)
                            private fun finish(id: String?, failed: Boolean) {
                                if (id != null) main.post { if (!closed) onFinished(id, failed) }
                            }
                        }) == TextToSpeech.SUCCESS
                }.getOrDefault(false)
                onReady(if (configured) EngineReadiness.READY else EngineReadiness.ENGINE_ERROR)
            } }
        }
        override fun voiceCatalog(): SpeechVoiceCatalog? {
            if (closed) return null
            val engine = voice ?: return null
            fun describe(value: android.speech.tts.Voice): SpeechVoice {
                val features = value.features
                return SpeechVoice(value.name, value.locale.language, value.locale.country,
                    value.isNetworkConnectionRequired,
                    features?.let { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it })
            }
            return SpeechVoiceCatalog(
                runCatching { engine.voices?.map(::describe) }.getOrNull(),
                runCatching { engine.voice?.let(::describe) }.getOrNull())
        }
        override fun selectVoice(name: String): Boolean {
            if (closed) return false
            val engine = voice ?: return false
            val candidate = engine.voices?.firstOrNull { it.name == name } ?: return false
            val features = candidate.features ?: return false
            if (candidate.locale.language != "tr" || TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in features) return false
            return engine.setVoice(candidate) == TextToSpeech.SUCCESS && engine.voice?.name == name
        }
        override fun speak(text: String, id: String) =
            !closed && voice?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        override fun stop() { voice?.stop() }
        override fun shutdown() { closed = true; val previous = voice; voice = null; previous?.shutdown() }
    }
    companion object {
        @Volatile private var instance: NavigationSpeech? = null
        fun get(context: Context): NavigationSpeech = instance ?: synchronized(this) {
            instance ?: NavigationSpeech(context).also { instance = it }
        }
    }
}
