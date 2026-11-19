package org.iz.navigation.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.IzApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class RedesignPaletteStateTest {
    @get:Rule val compose = createComposeRule()
    private val displayedPalette = AtomicReference<IzPalette?>()

    private fun withIsolatedPalette(block: (IzApplication) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IzApplication
        assertEquals("org.iz.navigation.qaredesign20261119", app.packageName)
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.INTERNET))
        val preferences = app.getSharedPreferences("iz_appearance", Context.MODE_PRIVATE)
        val original = preferences.getString("palette", null)
        try {
            savePalette(app, IzPalette.INDIGO)
            block(app)
        } finally {
            if (original == null) preferences.edit().remove("palette").apply()
            else preferences.edit().putString("palette", original).apply()
        }
    }

    @Composable private fun TestTheme(content: @Composable () -> Unit) {
        IzTheme {
            val palette = LocalIzAppearance.current.palette
            SideEffect { displayedPalette.set(palette) }
            content()
        }
    }

    private fun switchPalette(app: IzApplication, palette: IzPalette) {
        compose.runOnIdle { savePalette(app, palette) }
        compose.waitUntil(5_000) { displayedPalette.get() == palette }
    }

    @Test fun paletteChangeKeepsGroupInviteDraftWithoutSubmitting() = withIsolatedPalette { app ->
        assertNull(app.groups.state.value.groupId)
        compose.setContent { TestTheme { GroupScreen(app.groups, {}, { _, _ -> error("Unexpected route") }) } }
        compose.onNodeWithText("Davetle katıl").performClick()
        compose.onNodeWithText("Görünen adın").performTextReplacement("Deneme")
        compose.onNodeWithText("8 karakterli davet kodu").performTextReplacement("TEST1234")
        switchPalette(app, IzPalette.PETROL)
        compose.onNodeWithText("Deneme").assertExists()
        compose.onNodeWithText("TEST1234").assertExists()
        compose.onNodeWithText("Katılma isteği gönder").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertNull(app.groups.state.value.groupId) }
    }

    @Test fun paletteChangesKeepTheOpenPreviewAndTheStartedRouteAndRecording() = withIsolatedPalette { app ->
        SharedMapRequestUiFixture().use { fixture ->
            compose.setContent {
                TestTheme {
                    QuickDirectionsDialog(emptyList(), fixture.controller.state,
                        locate = fixture.controller::quickOrigin,
                        validateOrigin = fixture.controller::validateQuickOrigin,
                        plan = { origin, destination, mode, _ -> fixture.route(origin, destination, mode) },
                        activate = fixture.controller::startQuickGuidance,
                        onDismiss = {}, onStarted = { fixture.onStarted.incrementAndGet() },
                        hasLocationPermission = { true }, initialDestination = fixture.target,
                        sharedPreview = true, previewMap = { Text("Sentetik önizleme") })
                }
            }
            compose.onNodeWithTag("shared-preview").performClick()
            compose.onNodeWithTag("shared-start").assertIsEnabled()
            switchPalette(app, IzPalette.PETROL)
            compose.onNodeWithText("Sentetik önizleme").assertIsDisplayed()
            compose.runOnIdle { assertNull(fixture.controller.state.value.sessionId) }
            compose.onNodeWithTag("shared-start").performClick()
            compose.waitUntil(10_000) { fixture.controller.state.value.recording }
            val active = compose.runOnIdle { fixture.controller.state.value }
            assertTrue(active.guidance)
            assertNotNull(active.sessionId)
            assertNotNull(active.journey)
            assertEquals(fixture.target, active.route?.stops?.last())
            switchPalette(app, IzPalette.GRAPHITE_AMBER)
            compose.runOnIdle {
                val current = fixture.controller.state.value
                assertEquals(active.sessionId, current.sessionId)
                assertEquals(active.route, current.route)
                assertEquals(active.journey?.id, current.journey?.id)
                assertTrue(current.guidance && current.recording)
                assertEquals(1, fixture.runtime.started)
                assertEquals(0, fixture.runtime.finished)
            }
        }
    }
}
