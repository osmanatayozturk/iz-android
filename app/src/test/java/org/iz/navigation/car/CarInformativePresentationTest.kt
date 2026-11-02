package org.iz.navigation.car

import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.navigation.*
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class CarInformativePresentationTest {
    private val now = 1_000_000L
    private val a = WeatherCoordinate(41.0, 29.0)
    private val b = WeatherCoordinate(41.001, 29.001)
    private fun route() = PlannedRoute("auto-facts", listOf(RouteStop("Başlangıç", a), RouteStop("Hedef", b)),
        listOf(RouteVertex(a, 0.0), RouteVertex(b, 60.0)), 140.0, 60.0, now,
        maneuvers = listOf(RouteManeuver(10, "Sağa dönün", "Sağa dönün", emptyList(), 0, 1, 0.0, 60.0)))
    private fun current() = NavigationState(route = route(), guidance = true, gpsStale = false,
        progress = NavigationProgress(0.0, 60.0, 140.0, 0.0, 0, 50.0))

    @Test fun existingAddressDistinguishesSameNamesWithoutChangingThePlace() {
        val first = SelectedOsmPlace("Kafe", 41.0, 29.0, displayName = "Kafe, Birinci Sokak, İstanbul")
        val second = first.copy(displayName = "Kafe, İkinci Sokak, İstanbul")
        assertEquals("Birinci Sokak, İstanbul", carSearchSubtitle(first))
        assertEquals("İkinci Sokak, İstanbul", carSearchSubtitle(second))
        assertEquals("Kafe", first.name)
        assertEquals(41.0, first.latitude, 0.0)
        assertEquals("Adres bilgisi yok", carSearchSubtitle(first.copy(displayName = null)))
        assertEquals("Adres bilgisi yok", carSearchSubtitle(first.copy(displayName = "  Kafe  ")))
        assertEquals("Birinci Sokak", carSearchSubtitle(first.copy(displayName = " \n Birinci  Sokak ")))
    }

    @Test fun longNamePrefixDoesNotConsumeTheExistingAddressSubtitle() {
        val name = "Uzun yer adı ".repeat(20).trim()
        assertEquals("Ayırt eden sokak", carSearchSubtitle(SelectedOsmPlace(name, 41.0, 29.0,
            displayName = "$name, Ayırt eden sokak")))
    }

    @Test fun tomTomUsesActualTrafficAgeDelayAndExperimentalQualifier() {
        val data = route().copy(provider = RouteProvider.TOMTOM,
            traffic = RouteTrafficInfo(now - 120_000, 61.0, 100.0, experimental = true),
            providerWarnings = listOf("Kaplamasız yol tercihi doğrulanamadı."))
        val view = carRoutePreviewPresentation(data, now)
        assertTrue(view.source.contains("TomTom trafik"))
        assertTrue(view.source.contains("2 dk önce"))
        assertTrue(view.source.contains("+2 dk"))
        assertTrue(view.source.contains("Deneysel"))
        assertFalse(view.source.contains("Trafik hariç"))
        assertEquals(data.providerWarnings.single(), view.warnings)
        assertTrue(carRoutePreviewPresentation(data.copy(traffic = data.traffic!!.copy(fetchedAt = now - 301_000)), now)
            .source.contains("Trafik verisi eski"))
    }

    @Test fun absentTrafficUsesActualProviderAndUnavailableReason() {
        for (provider in RouteProvider.entries) {
            val view = carRoutePreviewPresentation(route().copy(provider = provider,
                trafficUnavailableReason = "Trafik yanıtı alınamadı."), now)
            assertTrue(view.source.contains(if (provider == RouteProvider.TOMTOM) "TomTom" else "Valhalla"))
            assertTrue(view.source.contains("Trafik hariç tahmin"))
            assertTrue(view.source.contains("Trafik yanıtı alınamadı."))
        }
        val inconsistent = route().copy(provider = RouteProvider.VALHALLA,
            traffic = RouteTrafficInfo(now, 0.0, null))
        assertFalse(carRoutePreviewPresentation(inconsistent, now).source.contains("TomTom trafik"))
    }

    @Test fun unknownOrFutureTrafficTimeNeverClaimsFreshness() {
        for (time in listOf(0L, now + 1, Long.MIN_VALUE)) {
            val view = carRoutePreviewPresentation(route().copy(provider = RouteProvider.TOMTOM,
                traffic = RouteTrafficInfo(time, 0.0, null)), now)
            assertTrue(view.source.contains("Trafik zamanı doğrulanamadı"))
            assertFalse(view.source.contains("az önce"))
        }
    }

    @Test fun boundedWarningsDoNotSilentlyLoseTheirExistence() {
        val warnings = listOf("Uyarı ".repeat(100), "İkinci sağlayıcı uyarısı")
        val text = carRoutePreviewPresentation(route().copy(providerWarnings = warnings), now).warnings!!
        assertTrue(text.length <= 250)
        assertTrue(text.contains("Telefonda"))
        assertTrue(text.contains("2"))
        assertNull(carRoutePreviewPresentation(route(), now).warnings)
    }

    @Test fun freshnessAndRealOperationsOutrankCachedProgressAndArrival() {
        val state = current()
        val progress = checkNotNull(state.progress)
        assertTrue(carCanPresentProgress(state))
        assertNull(carNavigationNotice(state))
        assertEquals("GPS konumu bekleniyor", carNavigationNotice(state.copy(gpsStale = true, arrived = true, loading = true)))
        assertEquals("Rota hazırlanıyor", carNavigationNotice(state.copy(guidance = false, loading = true)))
        assertEquals("Hedefe ulaşıldı", carNavigationNotice(state.copy(guidance = false, arrived = true)))
        assertEquals("Rota hazırlanıyor", carNavigationNotice(state.copy(loading = true,
            progress = progress.copy(offRoute = true))))
        assertEquals("Rota dışındasın. Yeni rota bekleniyor.", carNavigationNotice(state.copy(
            progress = progress.copy(offRoute = true))))
        assertFalse(carCanPresentProgress(state.copy(gpsStale = true)))
        assertFalse(carCanPresentProgress(state.copy(arrived = true)))
        assertFalse(carCanPresentProgress(state.copy(guidance = false)))
    }

    @Test fun missingOrInvalidProgressCannotCreateAnEstimateOrAnInventedFailure() {
        val state = current()
        val valid = checkNotNull(state.progress)
        for (progress in listOf<NavigationProgress?>(null, valid.copy(remainingMeters = Double.NaN),
            valid.copy(remainingSeconds = Double.POSITIVE_INFINITY),
            valid.copy(nextManeuverDistanceMeters = -1.0), valid.copy(maneuverIndex = -1))) {
            assertFalse(carCanPresentProgress(state.copy(progress = progress)))
            assertEquals("Rota bilgisi bekleniyor", carNavigationNotice(state.copy(progress = progress)))
        }
        assertFalse(carCanPresentProgress(state.copy(route = null)))
        assertEquals("Rota bilgisi bekleniyor", carNavigationNotice(state.copy(route = null)))
        val route = checkNotNull(state.route)
        val brokenRoute = route.copy(maneuvers = route.maneuvers.map { it.copy(beginElapsedSeconds = Double.NaN) })
        assertFalse(carCanPresentProgress(state.copy(route = brokenRoute)))
    }

    @Test fun simulationQualifiesTheRealManeuverWithoutInventingMeasuredProgress() {
        assertEquals("Sağa dönün · Simülasyon • telefonda", carManeuverCue("Sağa dönün", simulation = true))
        assertEquals("Sağa dönün", carManeuverCue("Sağa dönün", simulation = false))
        assertTrue(carCanPresentProgress(current().copy(simulation = true)))
        assertFalse(carCanPresentProgress(current().copy(simulation = true, gpsStale = true)))
    }

    @Test fun voiceReadinessIsNotAudibilityAndMuteIsNotFailure() {
        val ready = SpeechStatus(EngineReadiness.READY)
        val text = carVoiceNotice(ready, muted = false)
        assertTrue(text.contains("Sesin duyulduğu henüz doğrulanmadı"))
        assertFalse(text.contains("interkom bağlı", ignoreCase = true))
        assertEquals("Navigasyon sesi kapalı.", carVoiceNotice(ready, muted = true))
        assertNull(carVoiceFault(ready, guidance = true, muted = false))
        assertNull(carVoiceFault(SpeechStatus(EngineReadiness.ENGINE_ERROR), guidance = true, muted = true))
        assertNull(carVoiceFault(SpeechStatus(EngineReadiness.ENGINE_ERROR), guidance = false, muted = false))
        for (failure in listOf(EngineReadiness.ENGINE_ERROR, EngineReadiness.INIT_TIMEOUT,
            EngineReadiness.LANGUAGE_MISSING, EngineReadiness.LANGUAGE_UNSUPPORTED)) {
            assertEquals("Türkçe seslendirme kullanılamıyor. Görsel yönlendirme sürüyor.",
                carVoiceFault(SpeechStatus(failure), guidance = true, muted = false))
        }
    }

    @Test fun weatherAndTestOutputFailuresCannotBecomeTurnFailures() {
        for (channel in listOf(SpeechChannel.WEATHER, SpeechChannel.TEST)) {
            val status = SpeechStatus(EngineReadiness.READY, SpeechRequestFailure(channel, SpeechFailure.OUTPUT))
            assertNull(carVoiceFault(status, guidance = true, muted = false))
            assertFalse(carVoiceNotice(status, muted = false).contains("Dönüş anonsu"))
        }
        val denied = SpeechStatus(EngineReadiness.READY, SpeechRequestFailure(SpeechChannel.TURN, SpeechFailure.AUDIO_FOCUS))
        assertEquals("Dönüş anonsu duyurulamadı. Görsel yönlendirme sürüyor.",
            carVoiceFault(denied, guidance = true, muted = false))
        assertEquals("Navigasyon sesi kapalı.", carVoiceNotice(denied, muted = true))
    }

    @Test fun confirmedPauseSuppressesLiveGuidanceWithoutChangingGpsOrRecordingTruth() {
        val paused = current().copy(paused = true, recording = true, progressUpdatedAt = now)
        assertFalse(carCanPresentProgress(paused))
        assertEquals("Duraklama sürüyor. Varış tahmini bekliyor.", carNavigationNotice(paused))
        assertNull(carVoiceWarning(paused, SpeechStatus(EngineReadiness.ENGINE_ERROR)))
        assertFalse(paused.gpsStale)
        assertTrue(paused.recording)
        assertEquals("GPS konumu bekleniyor", carNavigationNotice(paused.copy(gpsStale = true)))
    }

    @Test fun preparedOffRouteRequiresInternetWhileActualLoadingAndGpsKeepTheirOwnMeaning() {
        val state = current().copy(preparedRouteFingerprint = "prepared-owner")
        val offRoute = state.copy(progress = checkNotNull(state.progress).copy(offRoute = true))
        assertEquals("Rota dışındasın. Yeni rota için internet gerekiyor.", carNavigationNotice(offRoute))
        assertFalse(carCanPresentProgress(offRoute))
        assertEquals("Rota hazırlanıyor", carNavigationNotice(offRoute.copy(loading = true)))
        assertEquals("GPS konumu bekleniyor", carNavigationNotice(offRoute.copy(gpsStale = true)))
        assertTrue(carCanPresentProgress(state))
        assertNull(carNavigationNotice(state)) // Prepared status must not replace the maneuver card.
    }

    @Test fun sharedOwnerControlsFreshnessWithoutAnAutoProgressTimer() {
        val current = current()
        for (time in listOf<Long?>(null, 0L, 1L, now)) {
            assertTrue(carCanPresentProgress(current.copy(progressUpdatedAt = time)))
            assertFalse(carCanPresentProgress(current.copy(progressUpdatedAt = time, gpsStale = true)))
        }
    }

    @Test fun preparedLocalVoiceUsesActualCapabilityWithoutClaimingAudibilityOrAConnection() {
        val labels = mapOf(
            LocalVoiceCapability.UNKNOWN to "Yerel Türkçe ses durumu doğrulanmadı.",
            LocalVoiceCapability.AVAILABLE to "Yerel Türkçe ses hazır.",
            LocalVoiceCapability.NETWORK_REQUIRED to "Türkçe ses internet gerektiriyor.",
            LocalVoiceCapability.MISSING to "Yerel Türkçe ses yok.",
            LocalVoiceCapability.SELECTION_FAILED to "Yerel Türkçe ses seçilemedi.",
        )
        for ((capability, label) in labels) {
            val status = SpeechStatus(EngineReadiness.READY, localVoice = capability,
                voiceMode = GuidanceVoiceMode.PREPARED_LOCAL)
            val notice = carVoiceNotice(status, muted = false)
            assertTrue(notice.contains(label))
            assertTrue(notice.contains("Sesin duyulduğu henüz doğrulanmadı"))
            assertFalse(notice.contains("interkom bağlı", ignoreCase = true))
            assertEquals("Navigasyon sesi kapalı.", carVoiceNotice(status, muted = true))
        }
        assertFalse(carVoiceNotice(SpeechStatus(EngineReadiness.READY,
            localVoice = LocalVoiceCapability.AVAILABLE), muted = false).contains("Yerel Türkçe ses hazır."))
    }

    @Test fun voiceWarningFollowsTheActualManeuverAndNeverOverridesUnavailableProgress() {
        val denied = SpeechStatus(EngineReadiness.READY, SpeechRequestFailure(SpeechChannel.TURN, SpeechFailure.AUDIO_FOCUS))
        val state = current()
        assertEquals("Son anons duyurulamadı.", carVoiceWarning(state, denied))
        assertEquals("Sağa dönün · Son anons duyurulamadı.",
            carManeuverCue("Sağa dönün", false, carVoiceWarning(state, denied)))
        assertTrue(carCanPresentProgress(state))
        for (unavailable in listOf(state.copy(gpsStale = true), state.copy(arrived = true),
            state.copy(loading = true), state.copy(progress = checkNotNull(state.progress).copy(offRoute = true)),
            state.copy(muted = true), state.copy(guidance = false))) {
            assertNull(carVoiceWarning(unavailable, denied))
        }
        assertNull(carVoiceWarning(state, SpeechStatus(EngineReadiness.READY)))
        assertEquals("Seslendirme kullanılamıyor.", carVoiceWarning(state, SpeechStatus(EngineReadiness.ENGINE_ERROR)))
        for (channel in listOf(SpeechChannel.TEST, SpeechChannel.WEATHER)) {
            assertNull(carVoiceWarning(state, SpeechStatus(EngineReadiness.READY, SpeechRequestFailure(channel, SpeechFailure.OUTPUT))))
        }
    }
}
