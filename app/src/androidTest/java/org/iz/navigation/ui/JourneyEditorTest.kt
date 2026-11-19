package org.iz.navigation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.ui.history.LocalUiExitGuard
import org.iz.navigation.ui.history.LocalUiOperationBusy
import org.iz.navigation.ui.history.UiExitConfirmation
import org.iz.navigation.ui.history.UiExitGuard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneyEditorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun passengerReclassificationPreservesWholeJourneyIdentityAndTimes() {
        val journey = Journey(transport = Transport.CAR, startedAt = 100, endedAt = 200, title = "Eve dönüş")
        var saved: Journey? = null
        compose.setContent { MaterialTheme { JourneyEditor(journey, {}, { saved = it }) } }
        compose.onNodeWithText("Yolcu").performClick()
        compose.onNodeWithText("Kaydet").performClick()
        compose.runOnIdle {
            assertEquals(journey.id, saved?.id)
            assertEquals(journey.startedAt, saved?.startedAt)
            assertEquals(journey.endedAt, saved?.endedAt)
            assertEquals(Transport.PASSENGER, saved?.transport)
        }
    }

    @Test fun toolbarBackUsesTheDirtyDraftGuardAndBusyDisablesEditingActions() {
        val journey = Journey(transport = Transport.WALK, title = "Sabah yürüyüşü")
        val busy = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val guard = UiExitGuard()
        var saves = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUiExitGuard provides guard, LocalUiOperationBusy provides { busy.value }) {
                    if (visible.value) JourneyEditor(journey, onDismiss = { visible.value = false }, onSave = { saves++ })
                    UiExitConfirmation(guard)
                }
            }
        }
        compose.onNodeWithTag("journey-editor-title").performTextReplacement("Düzenlenen başlık")
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("journey-editor-save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Geri").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithContentDescription("Geri").performClick()
        compose.onNodeWithTag("ui-exit-keep").performClick()
        compose.onNodeWithText("Düzenlenen başlık").assertExists()
        compose.onNodeWithContentDescription("Geri").performClick()
        compose.onNodeWithTag("ui-exit-discard").performClick()
        compose.onNodeWithTag("journey-editor").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves) }
    }
}
