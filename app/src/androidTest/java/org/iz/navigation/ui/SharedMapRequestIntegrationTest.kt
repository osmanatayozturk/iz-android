package org.iz.navigation.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Equivalent synthetic UI/core integration, NOT real Android recorder/GPS/Room acceptance.
 * Real production dialog guards feed real quickOrigin/validateQuickOrigin/startQuickGuidance.
 */
@RunWith(AndroidJUnit4::class)
class SharedMapRequestIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(fixture: SharedMapRequestUiFixture, configuration: MutableState<String>) {
        compose.setContent { IzTheme {
            QuickDirectionsDialog(emptyList(), fixture.controller.state,
                locate = fixture.controller::quickOrigin,
                validateOrigin = fixture.controller::validateQuickOrigin,
                plan = { origin, destination, mode, _ -> fixture.route(origin, destination, mode) },
                activate = fixture.controller::startQuickGuidance,
                onDismiss = {}, onStarted = { fixture.onStarted.incrementAndGet() },
                hasLocationPermission = { true }, initialDestination = fixture.target,
                sharedPreview = true, configuration = { configuration.value },
                previewMap = { Text("Synthetic provider route preview") })
        } }
        compose.onNodeWithTag("shared-preview").performClick()
        compose.onNodeWithTag("shared-start").assertIsEnabled()
    }

    private fun startAction(): () -> Boolean =
        checkNotNull(compose.onNodeWithTag("shared-start").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)

    @Test fun repeatedExplicitStartCommitsOneRealGuidanceAndOneSyntheticRecording() {
        SharedMapRequestUiFixture().use { fixture ->
            show(fixture, mutableStateOf("config-a"))
            compose.runOnIdle {
                assertNull(fixture.controller.state.value.sessionId)
                assertFalse(fixture.controller.state.value.guidance)
                assertTrue(fixture.runtime.journeys.value.isEmpty())
                assertEquals(0, fixture.runtime.started)
            }
            val click = startAction()
            // Exercise two taps holding the same old semantics action before recomposition.
            compose.runOnIdle { click(); click() }
            compose.waitUntil(10_000) { fixture.controller.state.value.recording }
            compose.runOnIdle {
                val state = fixture.controller.state.value
                assertTrue(state.guidance && state.recording)
                assertFalse(state.recordingPending)
                assertNotNull(state.sessionId)
                assertEquals(fixture.runtime.recordingId(), state.journey?.id)
                assertEquals("synthetic-shared-route", state.route?.id)
                assertEquals(listOf("shared-ui-record-1"), fixture.runtime.journeys.value.map { it.id })
                assertEquals(1, fixture.runtime.started)
                assertEquals(0, fixture.runtime.finished)
                assertEquals(1, fixture.onStarted.get())
            }
            compose.onNodeWithTag("shared-start").assertIsNotEnabled()
        }
    }

    @Test fun obsoletePreviewActionCannotChangeAReplacementSessionOrItsRecording() {
        SharedMapRequestUiFixture().use { fixture ->
            val oldSession = fixture.seedRecording()
            compose.waitUntil(10_000) { fixture.controller.state.value.recording }
            show(fixture, mutableStateOf("config-a"))
            val obsoleteClick = startAction()
            val replacementSession = fixture.replaceRecording()
            compose.waitUntil(10_000) { fixture.controller.state.value.sessionId == replacementSession &&
                fixture.controller.state.value.recording }
            assertNotEquals(oldSession, replacementSession)
            val before = fixture.controller.state.value
            val rows = fixture.runtime.journeys.value.toList()
            val recorder = fixture.runtime.recordingId()
            compose.onNodeWithTag("shared-start").assertIsNotEnabled()
            // A retained pre-recomposition click must recheck live ownership too.
            compose.runOnIdle { obsoleteClick() }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(before, fixture.controller.state.value)
                assertEquals(rows, fixture.runtime.journeys.value)
                assertEquals(recorder, fixture.runtime.recordingId())
                assertEquals(replacementSession, fixture.controller.state.value.sessionId)
                assertEquals(2, fixture.runtime.started)
                assertEquals(1, fixture.runtime.finished)
                assertEquals(0, fixture.onStarted.get())
            }
        }
    }

    @Test fun obsoletePreviewActionRechecksConfigurationAndPreservesAnActiveRecording() {
        SharedMapRequestUiFixture().use { fixture ->
            fixture.seedRecording()
            compose.waitUntil(10_000) { fixture.controller.state.value.recording }
            val configuration = mutableStateOf("config-a")
            show(fixture, configuration)
            val obsoleteClick = startAction()
            val before = fixture.controller.state.value
            val rows = fixture.runtime.journeys.value.toList()
            val recorder = fixture.runtime.recordingId()
            compose.runOnIdle { configuration.value = "config-b" }
            compose.onNodeWithTag("shared-start").assertIsNotEnabled()
            compose.runOnIdle { obsoleteClick() }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(before, fixture.controller.state.value)
                assertEquals(rows, fixture.runtime.journeys.value)
                assertEquals(recorder, fixture.runtime.recordingId())
                assertEquals(1, fixture.runtime.started)
                assertEquals(0, fixture.runtime.finished)
                assertEquals(0, fixture.onStarted.get())
            }
        }
    }
}

