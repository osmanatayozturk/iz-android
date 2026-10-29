package org.iz.navigation.ui

import org.iz.navigation.navigation.*

internal data class PhoneVoicePresentation(val readiness: String, val requestFailure: String?)
internal fun phoneVoicePresentation(status: SpeechStatus): PhoneVoicePresentation {
    val ready = when (status.readiness) {
        EngineReadiness.UNCHECKED -> "Ses motoru henüz kontrol edilmedi."
        EngineReadiness.INITIALIZING -> "Türkçe ses motoru hazırlanıyor."
        EngineReadiness.READY -> "Türkçe ses motoru hazır. Sesin duyulduğu henüz doğrulanmadı."
        EngineReadiness.ENGINE_ERROR -> "Ses motoru başlatılamadı."
        EngineReadiness.LANGUAGE_MISSING -> "Türkçe ses verisi eksik."
        EngineReadiness.LANGUAGE_UNSUPPORTED -> "Ses motoru Türkçeyi desteklemiyor."
        EngineReadiness.INIT_TIMEOUT -> "Ses motoru 10 saniyede yanıt vermedi."
    }
    val failure = status.requestFailure?.let { request ->
        val source = when (request.channel) { SpeechChannel.TURN -> "Dönüş anonsu"; SpeechChannel.WEATHER -> "Hava anonsu"; SpeechChannel.TEST -> "Ses denemesi" }
        source + when (request.reason) {
            SpeechFailure.AUDIO_FOCUS -> " ses çıkışı kullanılamadı."
            SpeechFailure.OUTPUT -> " seslendirilemedi."
        }
    }
    return PhoneVoicePresentation(ready, failure)
}
internal fun phoneVoiceFault(status: SpeechStatus, guidance: Boolean, muted: Boolean): String? {
    if (!guidance || muted) return null
    return when (status.readiness) {
        EngineReadiness.ENGINE_ERROR, EngineReadiness.LANGUAGE_MISSING, EngineReadiness.LANGUAGE_UNSUPPORTED,
        EngineReadiness.INIT_TIMEOUT -> "Türkçe seslendirme kullanılamıyor. Görsel yönlendirme sürüyor."
        else -> status.requestFailure?.takeIf { it.channel == SpeechChannel.TURN }?.let {
            "Dönüş anonsu duyurulamadı. Görsel yönlendirme sürüyor."
        }
    }
}
