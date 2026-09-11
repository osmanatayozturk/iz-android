package org.iz.navigation.watch

import android.graphics.Bitmap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class WatchScreenTest {
    @get:Rule val compose = createComposeRule()
    private val now = 1_000_000L
    private fun idle() = WatchUiState(phoneId = "phone", phoneName = "Telefon", snapshot = WearSnapshot(generatedAt = now), phoneVersion = 2)
    private fun active(weather: WearRouteWeather?) = idle().copy(
        phoneVersion = 3,
        snapshot = WearSnapshot(generatedAt = now, journeyId = "ride", mode = WearMode.MOTORCYCLE,
            startedAt = now - 300_000L, recording = true, distanceMeters = 4_200.0,
            elapsedMillis = 300_000L, weather = weather),
    )
    // Rotary focus lives on an outer node; the nested ScalingLazyColumn owns ScrollBy/ScrollToIndex.
    private fun watchList() = compose.onNode(
        hasScrollAction() and (hasTestTag("watch-list") or hasAnyAncestor(hasTestTag("watch-list"))),
        useUnmergedTree = true,
    )

    @Test fun automaticHealthOffersOneTimeArmingAndExplicitDisarming() {
        val health = mutableStateOf(WatchLiveHealthState())
        var armed = false
        var disarmed = false
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(idle(), now, {}, {}, {}, health.value,
            onArmHealth = { armed = true }, onDisarmHealth = { disarmed = true }) } }
        watchList().performScrollToNode(hasTestTag("arm-health"))
        compose.onNodeWithTag("arm-health").performClick()
        compose.runOnIdle { assertTrue(armed); health.value = WatchLiveHealthState(armed = true,
            status = "Kayıt yok · Sensörler kapalı") }
        watchList().performScrollToNode(hasText("Kayıt yok · Sensörler kapalı"))
        compose.onNodeWithText("Kayıt yok · Sensörler kapalı").assertIsDisplayed()
        compose.onNodeWithText("Canlı nabız").assertDoesNotExist()
        watchList().performScrollToNode(hasTestTag("disarm-health"))
        compose.onNodeWithTag("disarm-health").performClick()
        compose.runOnIdle { assertTrue(disarmed) }
    }

    @Test fun staleLiveHeartIsLabelledAsOldInsteadOfCurrent() {
        val health = WatchLiveHealthState(armed = true, onBody = true, capturing = true,
            journeyId = "ride", latestHeartRate = 95.0, latestHeartAt = now - 31_000,
            status = "Yolculuk ölçülüyor")
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(active(null), now, {}, {}, {}, health) } }
        watchList().performScrollToNode(hasText("Son nabız · eski ölçüm"))
        compose.onNodeWithText("Son nabız · eski ölçüm").assertIsDisplayed()
        compose.onNodeWithText("Canlı nabız").assertDoesNotExist()
    }

    @Test fun candidateProgressUsesEvidenceWindowInsteadOfWholeRouteDistance() {
        val state = idle().copy(phoneVersion = 4, snapshot = WearSnapshot(generatedAt = now,
            journeyId = "candidate", mode = WearMode.WALK, recording = true, temporary = true,
            startedAt = now - 600_000, deadlineAt = now + 300_000, distanceMeters = 900.0, candidateProgressMeters = 125.0))
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(state, now, {}, {}, {}) } }
        watchList().performScrollToNode(hasText("125 / 500 m"))
        compose.onNodeWithText("125 / 500 m").assertIsDisplayed()
        compose.onNodeWithText("500 / 500 m").assertDoesNotExist()
    }

    @Test fun weatherCardsRenderFreshExpiredAndDisconnectedFixtureFrames() {
        val ready = WearRouteWeather(WearWeatherStatus.READY, now - 60_000L, now + 3_540_000L,
            remainingMeters = 12_300.0, arrivalAt = now + 900_000L, hazardStartsAt = now + 300_000L,
            threshold = WearWeatherThreshold.EXCEEDED, headline = "Hava eşiği aşılıyor", detail = "Yağmur bekleniyor")
        val shown = mutableStateOf(active(ready))
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(shown.value, now, {}, {}, {}) } }
        val directory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "qa").apply { mkdirs() }
        fun capture(name: String, centerWeather: Boolean = false) {
            watchList().performScrollToNode(hasTestTag("weather-card"))
            if (centerWeather) watchList().performTouchInput {
                swipeUp(startY = 400f, endY = 300f, durationMillis = 500L)
            }
            compose.waitForIdle()
            val output = File(directory, name)
            output.outputStream().use { stream ->
                assertTrue(compose.onRoot().captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            assertTrue(output.length() > 0)
        }
        capture("task2-fresh-fixture.png")
        compose.runOnIdle { shown.value = active(ready.copy(validUntil = now - 1L)) }
        capture("task2-expired-fixture.png")
        compose.runOnIdle { shown.value = active(ready).copy(phoneId = null) }
        capture("task2-disconnected-fixture.png", centerWeather = true)
    }
    @Test fun nullWeatherHidesTheCardOnSmallWatch() {
        compose.setContent { Box(Modifier.size(192.dp)) { WatchScreen(active(null), now, {}, {}, {}) } }
        compose.onNodeWithTag("weather-card").assertDoesNotExist()
        compose.onNodeWithText("Yolculuk havası").assertDoesNotExist()
    }

    @Test fun loadingWeatherShowsBoundedPhoneProgressOnSmallWatch() {
        val weather = WearRouteWeather(WearWeatherStatus.LOADING, now - 10_000L, now - 10_000L,
            headline = "Hava hazırlanıyor")
        compose.setContent { Box(Modifier.size(192.dp)) { WatchScreen(active(weather), now, {}, {}, {}) } }
        watchList().performScrollToNode(hasTestTag("weather-card"))
        compose.onNodeWithTag("weather-card").assertIsDisplayed()
        compose.onNodeWithText("Hava hazırlanıyor").assertIsDisplayed()
        compose.onNodeWithText("Tahmin telefonda hesaplanıyor.").assertIsDisplayed()
    }

    @Test fun freshReadyWeatherShowsRouteTimesAndKeepsStopFlowOnLargeWatch() {
        val weather = WearRouteWeather(WearWeatherStatus.READY, now - 60_000L, now + 3_540_000L,
            remainingMeters = 12_300.0, arrivalAt = now + 900_000L, hazardStartsAt = now + 300_000L,
            threshold = WearWeatherThreshold.EXCEEDED, headline = "Hava eşiği aşılıyor", detail = "Yağmur bekleniyor")
        var stopped: String? = null
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(active(weather), now, {}, { stopped = it }, {}) } }
        for (text in listOf("Yolculuk havası", "Hava eşiği aşılıyor", "Yağmur bekleniyor")) {
            watchList().performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        watchList().performScrollToNode(hasText("Eşik yaklaşık", substring = true))
        compose.onNodeWithText("Varış yaklaşık", substring = true).assertIsDisplayed()
        watchList().performScrollToNode(hasTestTag("stop"))
        compose.onNodeWithTag("stop").performClick()
        watchList().performScrollToNode(hasTestTag("confirm-stop"))
        compose.onNodeWithTag("confirm-stop").performClick()
        compose.runOnIdle { assertEquals("ride", stopped) }
    }

    @Test fun expiredReadyWeatherIsDistinctFromPhoneDisconnection() {
        val weather = WearRouteWeather(WearWeatherStatus.READY, now - 3_600_000L, now - 1L,
            arrivalAt = now + 600_000L, headline = "Eşiklerin altında")
        compose.setContent { Box(Modifier.size(192.dp)) { WatchScreen(active(weather), now, {}, {}, {}) } }
        watchList().performScrollToNode(hasText("Tahmin süresi doldu"))
        compose.onNodeWithText("Tahmin süresi doldu").assertIsDisplayed()
        compose.onNode(hasText("verisi", substring = true) and hasAnyAncestor(hasTestTag("weather-card"))).assertDoesNotExist()
    }

    @Test fun weatherErrorUsesItsPhoneExplanationOnLargeWatch() {
        val weather = WearRouteWeather(WearWeatherStatus.ERROR, now - 60_000L, now + 3_540_000L,
            headline = "Tahmin kısmen eksik", detail = "Bazı bölümlerde tahmin eksik.")
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(active(weather), now, {}, {}, {}) } }
        watchList().performScrollToNode(hasText("Tahmin kısmen eksik"))
        compose.onNodeWithText("Tahmin kısmen eksik").assertIsDisplayed()
        compose.onNodeWithText("Bazı bölümlerde tahmin eksik.").assertIsDisplayed()
        compose.onNodeWithText("Tahmin kullanılamıyor").assertIsDisplayed()
    }
    @Test fun staleOrDisconnectedSnapshotNeutralizesForecastTiming() {
        val weather = WearRouteWeather(WearWeatherStatus.READY, now - 60_000L, now + 3_540_000L,
            arrivalAt = now + 900_000L, hazardStartsAt = now + 300_000L,
            threshold = WearWeatherThreshold.EXCEEDED, headline = "Hava eşiği aşılıyor")
        val shown = mutableStateOf(active(weather).copy(phoneId = null))
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(shown.value, now, {}, {}, {}) } }
        watchList().performScrollToNode(hasTestTag("weather-card"))
        compose.onNode(hasText("verisi", substring = true) and hasAnyAncestor(hasTestTag("weather-card")),
            useUnmergedTree = true).assertExists()
        compose.onNode(hasText("yakla", substring = true) and hasAnyAncestor(hasTestTag("weather-card")),
            useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            shown.value = active(weather).copy(snapshot = active(weather).snapshot!!.copy(
                generatedAt = now - WearProtocol.STATE_TTL_MS))
        }
        compose.onNode(hasText("verisi", substring = true) and hasAnyAncestor(hasTestTag("weather-card")),
            useUnmergedTree = true).assertExists()
        compose.onNode(hasText("yakla", substring = true) and hasAnyAncestor(hasTestTag("weather-card")),
            useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun roundIdleScreenOffersAllSixModesAndStartsTheChosenRun() {
        var requested: WearMode? = null
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(idle(), now, { requested = it }, {}, {}) } }
        for (mode in WearMode.entries) {
            watchList().performScrollToNode(hasText(mode.label()))
            compose.onNodeWithText(mode.label()).assertIsDisplayed()
        }
        compose.onNodeWithText("Koşu").performClick()
        watchList().performScrollToNode(hasTestTag("start"))
        compose.onNodeWithTag("start").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(WearMode.RUN, requested) }
    }

    @Test fun disconnectedScreenNeverEnablesStart() {
        var started = false
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(WatchUiState(), now, { started = true }, {}, {}) } }
        watchList().performScrollToNode(hasTestTag("start"))
        compose.onNodeWithTag("start").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertFalse(started) }
    }

    @Test fun stopRequiresConfirmationAndTargetsExactlyTheDisplayedJourney() {
        var stopped: String? = null
        val state = idle().copy(snapshot = WearSnapshot(generatedAt = now, journeyId = "walk", mode = WearMode.WALK,
            startedAt = now - 120_000, recording = true, distanceMeters = 250.0, elapsedMillis = 120_000, stepCount = 318))
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(state, now, {}, { stopped = it }, {}) } }
        watchList().performScrollToNode(hasTestTag("stop"))
        compose.onNodeWithTag("stop").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(stopped) }
        watchList().performScrollToNode(hasTestTag("confirm-stop"))
        compose.onNodeWithTag("confirm-stop").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("walk", stopped) }
    }

    @Test fun confirmationCannotStopAReplacementJourney() {
        val state = mutableStateOf(idle().copy(snapshot = WearSnapshot(generatedAt = now, journeyId = "old", mode = WearMode.WALK,
            recording = true, startedAt = now - 1_000)))
        var stopped: String? = null
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(state.value, now, {}, { stopped = it }, {}) } }
        watchList().performScrollToNode(hasTestTag("stop"))
        compose.onNodeWithTag("stop").assertIsDisplayed().performClick()
        compose.runOnIdle { state.value = state.value.copy(snapshot = state.value.snapshot!!.copy(journeyId = "new")) }
        compose.onNodeWithTag("confirm-stop").assertDoesNotExist()
        compose.runOnIdle { assertNull(stopped) }
    }

    @Test fun automaticCandidateShowsPhoneProgressAndFifteenMinuteCountdown() {
        val state = idle().copy(snapshot = WearSnapshot(generatedAt = now, journeyId = "candidate", mode = WearMode.WALK,
            recording = true, temporary = true, startedAt = now - 600_000, deadlineAt = now + 300_000, distanceMeters = 240.0))
        compose.setContent { Box(Modifier.size(240.dp)) { WatchScreen(state, now, {}, {}, {}) } }
        watchList().performScrollToNode(hasText("240 / 500 m"))
        compose.onNodeWithText("240 / 500 m").assertIsDisplayed()
        compose.onNodeWithText("Kalan 5:00").assertIsDisplayed()
    }

    @Test fun oldPhoneDisablesRunAndExplainsUpdate() {
        compose.setContent { Box(Modifier.size(192.dp)) { WatchScreen(idle().copy(phoneVersion = 1), now, {}, {}, {}) } }
        watchList().performScrollToNode(hasText("Koşu"))
        compose.onNodeWithText("Koşu").assertIsDisplayed().assertIsNotEnabled()
        watchList().performScrollToNode(hasText("Koşu ve sağlık özeti için telefondaki İz'i güncelle."))
        compose.onNodeWithText("Koşu ve sağlık özeti için telefondaki İz'i güncelle.").assertIsDisplayed()
    }

    @Test fun smallRoundRunScreenSeparatesPhoneStepsWatchStepsAndDelayedHealth() {
        val state = idle().copy(snapshot = WearSnapshot(generatedAt = now, journeyId = "run", mode = WearMode.RUN,
            startedAt = now - 600_000, recording = true, distanceMeters = 1_000.0, elapsedMillis = 600_000,
            averagePaceSecondsPerKm = 600.0, stepCount = 1_200,
            health = WearHealthSummary(latestHeartRateBpm = 128.0, latestHeartRateAt = now - 120_000,
                heartRateSampleCount = 1, watchSteps = 1_150, lastCheckedAt = now - 10_000, partial = true)))
        compose.setContent { Box(Modifier.size(192.dp)) { WatchScreen(state, now, {}, {}, {}) } }
        for (label in listOf("10:00 dk/km", "Telefonda ölçülen adımlar", "Saatte ölçülen adımlar", "Toplam enerji",
            "Kısmi sağlık verisi. Eksik ölçümler sıfır sayılmaz.")) {
            watchList().performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        watchList().performScrollToNode(hasText("Son aktarım kontrolü", substring = true))
        compose.onNodeWithText("Saat verisi gecikmeli gelebilir.", substring = true).assertIsDisplayed()
    }
}
