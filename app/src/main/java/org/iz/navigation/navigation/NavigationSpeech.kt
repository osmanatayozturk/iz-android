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
import java.util.Locale

/** One process-wide Turkish voice and audio-focus owner. Turns preempt weather. */
class NavigationSpeech private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var sequence = 0L
    private var pending: Speech? = null
    private var active: Speech? = null
    private var activeId: String? = null
    private var focus: AudioFocusRequest? = null
    private data class Speech(val text: String, val turn: Boolean, val test: Boolean, val expiresAt: Long)

    fun speakTurn(text: String) = post { submit(text, turn = true) }
    fun speakWeather(text: String) = post { submit(text, turn = false) }
    fun testWeather() = post {
        submit("İz yolculuk havası. Sesli uyarıları bu ses çıkışından duyacaksın.", false, true)
    }
    fun cancelTurns() = post { cancel(true) }
    fun cancelWeather() = post { cancel(false) }
    internal fun suspendAutomaticWeather() = post {
        if (pending?.test != true && active?.test != true) cancel(false)
    }

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun submit(text: String, turn: Boolean, test: Boolean = false) {
        if (text.isBlank()) return
        if (!turn && (active?.turn == true || pending?.turn == true)) return
        // Drop preempted weather, including any delayed engine-initialization request.
        stopActive()
        pending = Speech(text.take(500), turn, test, SystemClock.elapsedRealtime() + 15_000L)
        if (ready) drain() else initialize()
    }

    private fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            main.post {
                val engine = tts ?: return@post
                if (status != TextToSpeech.SUCCESS ||
                    engine.setLanguage(Locale.forLanguageTag("tr-TR")) < TextToSpeech.LANG_AVAILABLE) {
                    if (pending?.test == true) Toast.makeText(app,
                        "Türkçe seslendirme kullanılamıyor. Telefonun metin okuma ayarlarını kontrol et.",
                        Toast.LENGTH_LONG).show()
                    pending = null
                    engine.shutdown()
                    tts = null
                    return@post
                }
                engine.setAudioAttributes(attributes)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) = finished(id)
                    @Deprecated("Android legacy callback")
                    override fun onError(id: String?) = finished(id)
                    private fun finished(id: String?) {
                        main.post { if (id == activeId) { activeId = null; active = null; releaseFocus() } }
                    }
                })
                ready = true
                drain()
            }
        }
    }

    private fun drain() {
        val speech = pending ?: return
        pending = null
        if (speech.expiresAt < SystemClock.elapsedRealtime()) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) post { pending = null; stopActive() }
            }, main).build()
        if (!runCatching { audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)) {
            if (speech.test) Toast.makeText(app, "Ses çıkışı şu an kullanılamıyor.", Toast.LENGTH_LONG).show()
            return
        }
        focus = request
        active = speech
        val id = (++sequence).toString()
        activeId = id
        if (tts?.speak(speech.text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) stopActive()
    }

    private fun cancel(turn: Boolean) {
        if (pending?.turn == turn) pending = null
        if (active?.turn == turn) stopActive()
    }

    private fun stopActive() {
        active = null
        activeId = null
        tts?.stop()
        releaseFocus()
    }

    private fun releaseFocus() {
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
    }

    companion object {
        @Volatile private var instance: NavigationSpeech? = null
        fun get(context: Context): NavigationSpeech = instance ?: synchronized(this) {
            instance ?: NavigationSpeech(context).also { instance = it }
        }
    }
}
