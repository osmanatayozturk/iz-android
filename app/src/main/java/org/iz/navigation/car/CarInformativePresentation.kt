package org.iz.navigation.car

import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.EngineReadiness
import org.iz.navigation.navigation.SpeechChannel
import org.iz.navigation.navigation.SpeechStatus
import org.iz.navigation.navigation.GuidanceVoiceMode
import org.iz.navigation.navigation.LocalVoiceCapability
import org.iz.navigation.ui.phoneVoiceFault
import org.iz.navigation.ui.phoneVoicePresentation
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteProvider
import kotlin.math.ceil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.iz.navigation.navigation.PreparedRouteState
import org.iz.navigation.offline.OfflinePackStatus

internal data class CarPreparedPresentation(val route: String, val map: String)

/** Phone preparation does not prove that Auto's separate raster surface can render that corpus. */
internal fun carPreparedPresentation(state: PreparedRouteState, route: PlannedRoute?, mapReady: Boolean): CarPreparedPresentation {
    val routeMatches = state.route != null && !state.fingerprint.isNullOrBlank() && (route == null || state.route == route)
    val routeText = when {
        state.busy -> "Telefonda rota hazırlanıyor."
        !routeMatches -> "Telefonda rota çevrimdışı kullanım için hazırlanmadı."
        else -> "Telefonda rota hazır."
    }
    val pack = state.pack
    val mapText = when {
        state.busy -> "Telefonda harita alanı hazır değil."
        !routeMatches || pack == null -> "Telefonda harita alanı hazır değil."
        pack.request.routeId != state.route?.id || pack.request.sourceFingerprint != state.fingerprint -> "Telefonda harita alanı güncel değil."
        else -> when (pack.status) {
            OfflinePackStatus.PREPARED -> "Telefonda harita alanı hazır değil."
            OfflinePackStatus.DOWNLOADING -> "Telefonda harita alanı indiriliyor."
            OfflinePackStatus.PAUSED -> "Telefonda indirme duraklatıldı."
            OfflinePackStatus.PARTIAL -> "Telefonda indirme yarım kaldı."
            OfflinePackStatus.ERROR -> "Telefonda harita alanı hazırlanamadı. Mevcut rota korundu."
            OfflinePackStatus.READY -> if (mapReady && !state.busy) "Telefonda seçili harita alanı hazır."
                else "Telefonda harita alanı doğrulanamadı."
        }
    }
    return CarPreparedPresentation(routeText,
        "$mapText Auto haritası için internet gerekebilir; çevrimdışı kullanım doğrulanmadı.")
}

/** Each visible consumer owns this subscription and resumes with only the current owner facts. */
internal fun observeCarPreparedNotice(scope: CoroutineScope, lifecycle: Lifecycle,
    prepared: StateFlow<PreparedRouteState>, route: PlannedRoute?, mapReady: () -> Boolean,
    onNotice: (CarPreparedPresentation) -> Unit): Job = scope.launch {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        prepared.map { carPreparedPresentation(it, route, mapReady()) }
            .distinctUntilChanged().collect { onNotice(it) }
    }
}

/** Presentation of existing phone facts only: no engine, I/O, TTL or inferred connectivity. */
internal fun carCanPresentProgress(state: NavigationState): Boolean {
    val route = state.route ?: return false
    val progress = state.progress ?: return false
    val step = route.maneuvers.getOrNull(progress.maneuverIndex) ?: return false
    return state.guidance && !state.paused && !state.gpsStale && !state.loading && !state.arrived && !progress.arrived && !progress.offRoute &&
        step.beginElapsedSeconds.isFinite() && step.beginElapsedSeconds >= 0.0 &&
        listOf(progress.remainingMeters, progress.remainingSeconds, progress.elapsedSeconds,
            progress.nextManeuverDistanceMeters).all { it.isFinite() && it >= 0.0 }
}

internal fun carManeuverCue(instruction: String, simulation: Boolean, voiceWarning: String? = null): String =
    instruction.ifBlank { "Rotayı takip edin" } + (if (simulation) " · Simülasyon • telefonda" else "") +
        voiceWarning?.let { " · $it" }.orEmpty()

internal fun carNavigationNotice(state: NavigationState): String? = when {
    state.gpsStale -> "GPS konumu bekleniyor"
    state.paused -> "Duraklama sürüyor. Varış tahmini bekliyor."
    state.loading -> state.message?.takeIf { it.isNotBlank() } ?: "Rota hazırlanıyor"
    state.arrived || state.progress?.arrived == true -> "Hedefe ulaşıldı"
    state.progress?.offRoute == true -> if (state.preparedRouteFingerprint != null)
        "Rota dışındasın. Yeni rota için internet gerekiyor."
        else state.message?.takeIf { it.isNotBlank() } ?: "Rota dışındasın. Yeni rota bekleniyor."
    state.guidance && !carCanPresentProgress(state) -> "Rota bilgisi bekleniyor"
    else -> null
}

internal fun carVoiceNotice(status: SpeechStatus, muted: Boolean): String {
    val presentation = phoneVoicePresentation(status)
    val readiness = if (muted) "Navigasyon sesi kapalı." else presentation.readiness
    val failure = presentation.requestFailure?.takeUnless { muted && status.requestFailure?.channel == SpeechChannel.TURN }
    val local = if (muted || status.voiceMode != GuidanceVoiceMode.PREPARED_LOCAL) null else when (status.localVoice) {
        LocalVoiceCapability.UNKNOWN -> "Yerel Türkçe ses durumu doğrulanmadı."
        LocalVoiceCapability.AVAILABLE -> "Yerel Türkçe ses hazır."
        LocalVoiceCapability.NETWORK_REQUIRED -> "Türkçe ses internet gerektiriyor."
        LocalVoiceCapability.MISSING -> "Yerel Türkçe ses yok."
        LocalVoiceCapability.SELECTION_FAILED -> "Yerel Türkçe ses seçilemedi."
    }
    return listOfNotNull(readiness, local, failure).joinToString(" ")
}

internal fun carVoiceFault(status: SpeechStatus, guidance: Boolean, muted: Boolean): String? =
    phoneVoiceFault(status, guidance, muted)

internal fun carVoiceWarning(state: NavigationState, speech: SpeechStatus): String? {
    if (!carCanPresentProgress(state) || carVoiceFault(speech, state.guidance, state.muted) == null) return null
    return when (speech.readiness) {
        EngineReadiness.ENGINE_ERROR, EngineReadiness.LANGUAGE_MISSING,
        EngineReadiness.LANGUAGE_UNSUPPORTED, EngineReadiness.INIT_TIMEOUT -> "Seslendirme kullanılamıyor."
        else -> "Son anons duyurulamadı."
    }
}

internal fun carSearchSubtitle(place: SelectedOsmPlace): String {
    val name = place.name.readable()
    val display = place.displayName?.readable().orEmpty()
    val address = when {
        display.equals(name, ignoreCase = true) -> ""
        name.isNotEmpty() && display.startsWith("$name,", ignoreCase = true) -> display.drop(name.length + 1).trim()
        else -> display
    }
    return address.ifBlank { "Adres bilgisi yok" }.take(250)
}

internal data class CarRoutePreviewPresentation(val source: String, val warnings: String?)

internal fun carRoutePreviewPresentation(route: PlannedRoute, now: Long): CarRoutePreviewPresentation {
    val traffic = route.traffic
    val source = if (route.provider == RouteProvider.TOMTOM && traffic != null) {
        val age = if (now <= 0 || traffic.fetchedAt <= 0 || traffic.fetchedAt > now) "Trafik zamanı doğrulanamadı" else {
            val seconds = (now - traffic.fetchedAt) / 1000
            if (seconds > 300) "Trafik verisi eski · ${seconds / 60} dk önce"
            else "Trafik verisi ${if (seconds < 60) "az önce" else "${seconds / 60} dk önce"} güncellendi"
        }
        "TomTom trafik · +${ceil(traffic.delaySeconds / 60).toLong()} dk gecikme. $age" +
            if (traffic.experimental) ". Deneysel trafik tahmini" else ""
    } else {
        val provider = if (route.provider == RouteProvider.TOMTOM) "TomTom" else "Valhalla"
        "$provider · Trafik hariç tahmin" + route.trafficUnavailableReason?.readable()?.takeIf { it.isNotEmpty() }
            ?.let { ". $it" }.orEmpty()
    }
    val warnings = route.providerWarnings.map { it.readable() }.filter { it.isNotEmpty() }.distinct()
    val full = warnings.joinToString(" · ")
    val warningText = when {
        warnings.isEmpty() -> null
        full.length <= 250 -> full
        else -> {
            val suffix = "… ${warnings.size} rota uyarısı. Telefonda tamamını kontrol et."
            full.take(250 - suffix.length) + suffix
        }
    }
    val sourceSuffix = "… Ayrıntılar telefonda."
    val boundedSource = if (source.length <= 250) source else source.take(250 - sourceSuffix.length) + sourceSuffix
    return CarRoutePreviewPresentation(boundedSource, warningText)
}

private fun String.readable(): String = replace(Regex("\\s+"), " ").trim()
