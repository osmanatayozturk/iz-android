package org.iz.navigation.ui

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RedesignSectionEntryUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun phoneSectionsReturnToMapWithoutCreatingASession() {
        val app = requireIsolatedApp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
            compose.onNodeWithTag("home-record").assertIsDisplayed().performClick()
            compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
            compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
            visit("menu-route-plan", { compose.onNodeWithTag("navigation-screen").assertIsDisplayed() })
            visit("menu-journeys", { compose.onNode(hasText("Yolculuklar") and !hasClickAction()).assertIsDisplayed() })
            visit("menu-osm", { compose.onNodeWithTag("osm-contribution-hub").assertIsDisplayed() })
            visit("menu-weather", { compose.onNodeWithTag("weather_planner").assertIsDisplayed() })
            visit("menu-settings", {
                compose.onNodeWithTag("settings").assertIsDisplayed()
                compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-open-detection"))
                compose.onNodeWithTag("settings-open-detection").performClick()
                compose.onNodeWithTag("settings-view-detection").assertIsDisplayed()
                pressBack()
                compose.onNodeWithTag("settings").assertIsDisplayed()
            })
            compose.runOnIdle { assertIdleNavigation(app.navigation.state.value) }
        }
    }

    @Test fun voiceLayersReturnsToVoiceAndSharesTheSavedRouteChoiceWithTheMapMenu() {
        val app = requireIsolatedApp()
        val preferences = app.getSharedPreferences("navigation_map_view", Context.MODE_PRIVATE)
        val original = preferences.all["show_route"] as? Boolean
        val saved = !(original ?: true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("home-menu").performClick()
                compose.onNodeWithTag("menu-settings").performScrollTo().performClick()
                compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-open-voice"))
                compose.onNodeWithTag("settings-open-voice").performClick()
                compose.onNodeWithText("Harita katmanları").performScrollTo().performClick()
                compose.onNodeWithTag("map-layers-settings").assertIsDisplayed()
                compose.onNodeWithTag("map-layer-route").performScrollTo().performClick()
                compose.onNodeWithTag("map-layer-dismiss").performClick()
                compose.onNodeWithTag("settings-view-voice").assertIsDisplayed()

                compose.onNodeWithText("Harita katmanları").performScrollTo().performClick()
                val route = compose.onNodeWithTag("map-layer-route").performScrollTo()
                if (saved) route.assertIsOn() else route.assertIsOff()
                pressBack()
                compose.onNodeWithTag("map-layers-settings").assertDoesNotExist()
                compose.onNodeWithTag("settings-view-voice").assertIsDisplayed()

                pressBack()
                compose.onNodeWithTag("settings").assertIsDisplayed()
                pressBack()
                compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
                compose.onNodeWithTag("home-menu").performClick()
                compose.onNodeWithTag("menu-map-layers").performScrollTo().performClick()
                compose.onNodeWithText(if (saved) "✓ Planlanan rota" else "Planlanan rota").assertIsDisplayed()
                pressBack()
                compose.runOnIdle { assertIdleNavigation(app.navigation.state.value) }
            }
        } finally {
            val reset = preferences.edit()
            if (original == null) reset.remove("show_route") else reset.putBoolean("show_route", original)
            reset.apply()
        }
    }

    @Test fun journalSubpagesReturnToTheSameCompletedSyntheticJourney() {
        val app = requireIsolatedApp()
        val repository = DiaryRepository(app)
        val now = System.currentTimeMillis()
        val journey = Journey(id = UUID.randomUUID().toString(), title = "Sentetik tamamlanmış yolculuk",
            transport = Transport.MOTORCYCLE, status = JourneyStatus.CONFIRMED,
            startedAt = now - 60_000L, endedAt = now, interrupted = true)
        assertEquals(DiarySnapshot(), runBlocking { repository.snapshot() })
        runBlocking { repository.saveJourney(journey) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("home-menu").performClick()
                compose.onNodeWithTag("menu-journeys").performScrollTo().performClick()
                visitJournalLink(journey.title)

                val subpages = listOf(
                    "İzi haritada aç" to "Konum kaydında kesinti var.",
                    "Süre ve mesafe" to "Yolculuk istatistikleri",
                    "Ziyaretler" to "Bu yolculukta kayıtlı ziyaret yok.",
                    "Fotoğraflar" to "Bu yolculukta fotoğraf yok.",
                    "Raporlar" to "Viraj ve yatış raporu",
                )
                for ((entry, heading) in subpages) {
                    visitJournalLink(entry)
                    compose.onNodeWithText(heading).assertIsDisplayed()
                    pressBack()
                    val pageTitle = hasText(journey.title) and !hasClickAction()
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(pageTitle)
                    compose.onNode(pageTitle).assertIsDisplayed()
                }
                assertIdleNavigation(app.navigation.state.value)
                assertEquals(journey, runBlocking { repository.getJourney(journey.id) })
            }
        } finally {
            runBlocking {
                assertEquals(journey, repository.getJourney(journey.id))
                repository.deleteJourney(journey.id)
                assertEquals(DiarySnapshot(), repository.snapshot())
            }
        }
    }

    private fun requireIsolatedApp(): IzApplication {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IzApplication
        assertEquals("org.iz.navigation.qaredesign20261119", app.packageName)
        assertTrue(app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        for (permission in listOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION)) {
            assertEquals(permission, PackageManager.PERMISSION_DENIED, app.checkSelfPermission(permission))
        }
        assertIdleNavigation(app.navigation.state.value)
        return app
    }

    private fun visitJournalLink(label: String) {
        val link = hasText(label) and hasClickAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(link)
        compose.onNode(link).assertIsDisplayed().performClick()
    }

    private fun visit(menuTag: String, assertDestination: () -> Unit) {
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag(menuTag).performScrollTo().assertIsDisplayed().performClick()
        assertDestination()
        pressBack()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
    }

    private fun assertIdleNavigation(state: NavigationState) {
        assertNull(state.sessionId)
        assertNull(state.journey)
        assertNull(state.route)
        assertNull(state.trackFollow)
        assertFalse(state.guidance)
        assertFalse(state.recording)
    }
}
