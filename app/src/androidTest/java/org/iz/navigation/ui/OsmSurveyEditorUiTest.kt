package org.iz.navigation.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.*
import org.iz.navigation.integration.SurveyDayState
import org.iz.navigation.integration.SurveyHoursInterval
import org.iz.navigation.integration.SurveyOpeningHours
import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.iz.navigation.integration.osm.OsmCorrectionReview
import org.iz.navigation.integration.osm.OsmCredentials
import org.iz.navigation.integration.osm.OsmUser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Survey editor follows the existing correction editor callback boundary and the shared survey payload. */
@RunWith(AndroidJUnit4::class)
class OsmSurveyEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 11, 11)
    private val credentials = OsmCredentials("synthetic-survey-token", OsmUser(41, "Sentetik haritacı"), null, setOf("write_api"))

    private fun element(hours: String = "Mo-Fr 09:00-18:00; PH off") = OsmCorrectionElement(
        ref = OsmRef(OsmType.NODE, 700), version = 12, changesetId = 99, userId = 30,
        latitude = "41.0", longitude = "29.0", nodeRefs = emptyList(),
        tags = mapOf("amenity" to "cafe", "name" to "Sentetik kafe", "opening_hours" to hours,
            "wheelchair" to "limited", "check_date:opening_hours" to "2020-01-02", "check_date:wheelchair" to "2021-03-04"),
    )

    private fun draft(target: OsmCorrectionElement): MapEditDraft {
        val observation = SurveyObservation(baseVersion = target.version, observedOn = today.toString(),
            hours = SurveyFieldObservation(before = target.tags["opening_hours"], beforeDate = target.tags["check_date:opening_hours"]),
            wheelchair = SurveyFieldObservation(before = target.tags["wheelchair"], beforeDate = target.tags["check_date:wheelchair"]))
        return MapEditDraft(id = "11111111-1111-4111-8111-111111111111", latitude = 41.0, longitude = 29.0,
            targetType = target.ref.type, targetId = target.ref.id, surveyPayload = observation.encode())
    }

    private fun show(target: OsmCorrectionElement, initial: MapEditDraft = draft(target),
        onSave: (MapEditDraft) -> Unit = {}, onReview: (MapEditDraft) -> Unit = {}, onPublish: (OsmCorrectionReview) -> Unit = {},
        readTarget: OsmCorrectionElement = target, reviewTarget: OsmCorrectionElement = target) {
        compose.setContent { MaterialTheme {
            OsmSurveyEditor(initial = initial, target = target, today = today,
                busy = false, canWriteMap = true, authBusy = false, authError = null,
                liveBaseline = { initial }, liveBusy = { false }, onClose = {}, onSave = onSave,
                onReadTarget = { readTarget }, onReview = { value -> onReview(value); reviewTarget.review(value, credentials) },
                isReviewCurrent = { it.credentials === credentials }, onLogin = { error("Unexpected OAuth") },
                onPublish = onPublish, onNoteFallback = {})
        } }
    }

    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("osm-survey-editor-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test fun unknownKeepsBothPublicValuesAndVerificationDates() {
        val target = element()
        var saved: MapEditDraft? = null
        var reviews = 0
        show(target, onSave = { saved = it }, onReview = { reviews++ })
        node("osm-survey-opening_hours-UNKNOWN").assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        node("osm-survey-wheelchair-UNKNOWN").performClick().assertIsSelected()
        node("osm-survey-save").performClick()
        compose.runOnIdle {
            val observation = requireNotNull(saved?.surveyObservation())
            assertEquals(SurveyAction.UNKNOWN, observation.hours.action)
            assertEquals(SurveyAction.UNKNOWN, observation.wheelchair.action)
            assertEquals("2020-01-02", observation.hours.beforeDate)
            assertEquals("2021-03-04", observation.wheelchair.beforeDate)
            assertEquals(emptyMap<String, String>(), observation.publicChanges(target.tags, today))
            assertEquals(0, reviews)
        }
    }

    @Test fun invalidAdvancedTextSurvivesSaveAndReopenWhileReviewStaysBlocked() {
        val target = element()
        val raw = "  Mo-Fr 99:99-18:00; PH off; unfinished  "
        var saved = draft(target)
        var reviews = 0
        compose.setContent { MaterialTheme {
            var open by remember { mutableStateOf(true) }
            if (open) OsmSurveyEditor(initial = saved, target = target, today = today,
                busy = false, canWriteMap = true, authBusy = false, authError = null,
                liveBaseline = { saved }, liveBusy = { false }, onClose = { open = false },
                onSave = { saved = it; open = false }, onReadTarget = { target },
                onReview = { value -> reviews++; target.review(value, credentials) },
                isReviewCurrent = { true }, onLogin = { error("Unexpected OAuth") },
                onPublish = { error("Invalid text must not publish") }, onNoteFallback = {})
            else Button(onClick = { open = true }) { Text("Taslağı yeniden aç") }
        } }
        node("osm-survey-opening_hours-EDIT").performClick()
        compose.onNodeWithTag("osm-survey-hours-simple").assertDoesNotExist()
        node("osm-survey-hours-advanced").performTextReplacement(raw)
        node("osm-survey-review").assertIsNotEnabled()
        node("osm-survey-save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(raw, saved.surveyObservation()?.hours?.value) }
        compose.onNodeWithText("Taslağı yeniden aç").performClick()
        node("osm-survey-hours-advanced").assertTextContains(raw)
        node("osm-survey-review").assertIsNotEnabled()
        compose.onNodeWithTag("osm-survey-editor-list").performScrollToNode(hasText("Saat ifadesini kontrol et. Taslağın saklanıyor."))
        compose.onNodeWithText("Saat ifadesini kontrol et. Taslağın saklanıyor.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, reviews) }
    }

    @Test fun unchangedVerificationReviewsOnlyItsDateAndStillRequiresFinalConsent() {
        val target = element("Mo-Fr 09:00-18:00")
        val sent = mutableListOf<OsmCorrectionReview>()
        show(target, onPublish = { sent += it })
        node("osm-survey-opening_hours-VERIFY").performClick().assertIsSelected()
        node("osm-survey-observed-on").performTextReplacement("2026-11-12")
        node("osm-survey-confirmed").performClick()
        node("osm-survey-review").assertIsNotEnabled()
        node("osm-survey-observed-on").performTextReplacement("2026-11-10")
        node("osm-survey-confirmed").performClick()
        node("osm-survey-review").assertIsEnabled().performClick()
        node("osm-survey-publish").assertIsNotEnabled()
        compose.onNodeWithTag("osm-survey-editor-list").performScrollToNode(hasText("OSM hesabı · Sentetik haritacı"))
        compose.onNodeWithText("OSM hesabı · Sentetik haritacı").assertIsDisplayed()
        compose.onNodeWithTag("osm-survey-editor-list").performScrollToNode(hasText("Önce: 2020-01-02"))
        compose.onNodeWithText("Önce: 2020-01-02").assertIsDisplayed()
        compose.onNodeWithText("Sonra: 2026-11-10").assertIsDisplayed()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        node("osm-survey-reviewed").performClick()
        node("osm-survey-publish").performClick()
        compose.runOnIdle {
            val review = sent.single()
            assertEquals(target.tags + ("check_date:opening_hours" to "2026-11-10"), review.corrected.tags)
            assertEquals("", review.draft.name)
            assertEquals("", review.draft.street)
            assertEquals("", review.draft.houseNumber)
            assertEquals("2026-11-10", review.draft.surveyObservation()?.observedOn)
        }
    }

    @Test fun wheelchairOptionsSaveTheExplicitOsmValues() {
        val target = element()
        var saved: MapEditDraft? = null
        show(target, onSave = { saved = it })
        node("osm-survey-wheelchair-EDIT").performClick()
        for (value in listOf("yes", "limited", "no")) {
            node("osm-survey-wheelchair-$value").assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
            node("osm-survey-save").performClick()
            compose.runOnIdle {
                val field = requireNotNull(saved?.surveyObservation()).wheelchair
                assertEquals(SurveyAction.EDIT, field.action)
                assertEquals(value, field.value)
                assertEquals("limited", field.before)
                assertTrue(field.fullyObserved)
            }
        }
    }

    @Test fun weeklyFormKeepsUnknownClosedMultipleAndOvernightIntervalsDistinct() {
        val target = element("Mo-Su 09:00-18:00")
        var saved: MapEditDraft? = null
        show(target, onSave = { saved = it })
        node("osm-survey-opening_hours-EDIT").performClick()
        node("osm-survey-hours-simple").performClick()
        node("osm-survey-hours-day-0-UNKNOWN").performClick()
        node("osm-survey-hours-day-1-CLOSED").performClick()
        node("osm-survey-hours-day-2-0-from").performTextReplacement("12:00")
        node("osm-survey-hours-day-2-0-until").performTextReplacement("14:00")
        node("osm-survey-hours-day-2-add-interval").performClick()
        node("osm-survey-hours-day-2-1-from").performTextReplacement("22:00")
        node("osm-survey-hours-day-2-1-until").performTextReplacement("02:00")
        node("osm-survey-save").performClick()
        compose.runOnIdle {
            val observation = requireNotNull(saved?.surveyObservation())
            val week = requireNotNull(SurveyOpeningHours.toWeek(observation.hours.value))
            assertEquals(SurveyDayState.UNKNOWN, week.days[0].state)
            assertEquals(SurveyDayState.CLOSED, week.days[1].state)
            assertEquals(listOf(SurveyHoursInterval("12:00", "14:00"), SurveyHoursInterval("22:00", "02:00")), week.days[2].intervals)
            assertEquals(listOf(SurveyHoursInterval("09:00", "18:00")), week.days[3].intervals)
            assertFalse(observation.hours.fullyObserved)
            assertFalse(observation.publicChanges(target.tags, today).containsKey("check_date:opening_hours"))
        }
    }

    @Test fun newOpenIntervalsStayBlankUntilObservedTimesAreEntered() {
        val target = element("Mo-Su off")
        var saved by mutableStateOf(draft(target))
        var visible by mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            if (visible) OsmSurveyEditor(initial = saved, target = target, today = today,
                busy = false, canWriteMap = true, authBusy = false, authError = null,
                liveBaseline = { saved }, liveBusy = { false }, onClose = { visible = false },
                onSave = { saved = it; visible = false }, onReadTarget = { target },
                onReview = { target.review(it, credentials) }, isReviewCurrent = { true },
                onLogin = {}, onPublish = {}, onNoteFallback = {})
            else Button(onClick = { visible = true }) { Text("Eksik saat taslağını yeniden aç") }
        } }
        node("osm-survey-opening_hours-EDIT").performClick()
        node("osm-survey-hours-simple").performClick()
        node("osm-survey-hours-day-2-OPEN").performClick()
        node("osm-survey-hours-day-2-0-from").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        node("osm-survey-hours-day-2-0-until").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        node("osm-survey-hours-day-2-add-interval").performClick()
        node("osm-survey-hours-day-2-1-from").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        node("osm-survey-hours-day-2-1-until").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithTag("osm-survey-editor-list").performScrollToNode(hasText("Gelişmiş OSM ifadesi"))
        compose.onNodeWithText("Gelişmiş OSM ifadesi").performClick()
        node("osm-survey-hours-simple").performClick()
        restoration.emulateSavedInstanceStateRestore()
        node("osm-survey-hours-day-2-1-from").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        node("osm-survey-hours-complete").assertIsNotEnabled()
        node("osm-survey-save").performClick()
        compose.runOnIdle {
            val hours = requireNotNull(saved.surveyObservation()).hours
            assertFalse(SurveyOpeningHours.isValid(hours.value))
            assertFalse(hours.fullyObserved)
            assertEquals(hours.value, SurveyOpeningHours.fromDraft(requireNotNull(hours.formDraft))?.expression())
        }
        compose.onNodeWithText("Eksik saat taslağını yeniden aç").performClick()
        node("osm-survey-hours-day-2-0-from").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        node("osm-survey-hours-day-2-0-from").performTextReplacement("12:00")
        node("osm-survey-hours-day-2-0-until").performTextReplacement("14:00")
        node("osm-survey-hours-day-2-1-from").performTextReplacement("22:00")
        node("osm-survey-hours-day-2-1-until").performTextReplacement("02:00")
        node("osm-survey-save").performClick()
        compose.runOnIdle {
            val observation = requireNotNull(saved.surveyObservation())
            assertTrue(SurveyOpeningHours.isValid(observation.hours.value))
            assertNotNull(observation.hours.formDraft)
            assertEquals(setOf("opening_hours"), observation.publicChanges(target.tags, today).keys)
        }
    }

    @Test fun futureDatedDraftCanReopenAndSaveButCannotBeReviewed() {
        val target = element()
        val initial = draft(target).let { it.copy(surveyPayload = requireNotNull(it.surveyObservation())
            .copy(observedOn = "2027-11-11").encode()) }
        var saved: MapEditDraft? = null
        show(target, initial, onSave = { saved = it })
        node("osm-survey-observed-on").assertTextContains("2027-11-11")
        node("osm-survey-opening_hours-VERIFY").performClick()
        node("osm-survey-confirmed").performClick()
        node("osm-survey-review").assertIsNotEnabled()
        node("osm-survey-save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("2027-11-11", saved?.surveyObservation()?.observedOn) }
    }

    @Test fun oversizedAdvancedPasteIsExplicitlyRejectedWithoutLosingAcceptedText() {
        val accepted = "Mo-Fr 09:00-18:00; PH off"
        var saved: MapEditDraft? = null
        show(element(accepted), onSave = { saved = it })
        node("osm-survey-opening_hours-EDIT").performClick()
        node("osm-survey-hours-advanced").performTextReplacement("x".repeat(8193))
        compose.onNodeWithTag("osm-survey-editor-list").performScrollToNode(
            hasText("Taslak alanı en fazla 8192 karakter olabilir. Son kabul edilen metin korunuyor."))
        compose.onNodeWithText("Taslak alanı en fazla 8192 karakter olabilir. Son kabul edilen metin korunuyor.")
            .assertIsDisplayed()
        node("osm-survey-hours-advanced").assertTextContains(accepted)
        node("osm-survey-save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(accepted, saved?.surveyObservation()?.hours?.value) }
    }

    @Test fun manuallyChangedAdvancedTextClearsPrivateWeeklyState() {
        val target = element("Mo-Su 09:00-18:00")
        val week = requireNotNull(SurveyOpeningHours.toWeek(target.tags.getValue("opening_hours")))
        val initial = draft(target).let { draft ->
            val observation = requireNotNull(draft.surveyObservation())
            draft.copy(surveyPayload = observation.copy(hours = observation.hours.copy(
                action = SurveyAction.EDIT, value = week.expression(), formDraft = week.encodeDraft())).encode())
        }
        var saved: MapEditDraft? = null
        show(target, initial, onSave = { saved = it })
        compose.onNodeWithTag("osm-survey-editor-list").performScrollToNode(hasText("Gelişmiş OSM ifadesi"))
        compose.onNodeWithText("Gelişmiş OSM ifadesi").performClick()
        val advanced = "Mo-Fr 10:00-17:00; PH off"
        node("osm-survey-hours-advanced").performTextReplacement(advanced)
        node("osm-survey-save").performClick()
        compose.runOnIdle {
            val hours = requireNotNull(saved?.surveyObservation()).hours
            assertEquals(advanced, hours.value)
            assertNull(hours.formDraft)
        }
        compose.onNodeWithTag("osm-survey-hours-simple").assertDoesNotExist()
    }

    @Test fun designatedVerificationKeepsPublicValueAndChangesOnlyItsDate() {
        val target = element().let { it.copy(tags = it.tags + ("wheelchair" to "designated")) }
        var reviewed: MapEditDraft? = null
        show(target, onReview = { reviewed = it })
        node("osm-survey-wheelchair-VERIFY").assertIsEnabled().performClick()
        node("osm-survey-confirmed").performClick()
        node("osm-survey-review").assertIsEnabled().performClick()
        compose.runOnIdle {
            val observation = requireNotNull(reviewed?.surveyObservation())
            assertEquals("designated", observation.wheelchair.before)
            assertEquals(mapOf("check_date:wheelchair" to today.toString()), observation.publicChanges(target.tags, today))
        }
    }

    @Test fun newerObjectRequiresExplicitRebaseAndRenewedObservationConsent() {
        val target = element("Mo-Fr 09:00-18:00")
        val fresh = target.copy(version = 13, tags = target.tags + ("opening_hours" to "Mo-Su 10:00-17:00"))
        var saved: MapEditDraft? = null
        var reviewed: MapEditDraft? = null
        show(target, onSave = { saved = it }, onReview = { reviewed = it }, readTarget = fresh, reviewTarget = fresh)
        node("osm-survey-opening_hours-VERIFY").performClick()
        node("osm-survey-confirmed").performClick()
        node("osm-survey-read-current").performClick()
        node("osm-survey-save").performClick()
        compose.runOnIdle { assertEquals(12L, saved?.surveyObservation()?.baseVersion) }
        node("osm-survey-accept-current").performClick()
        node("osm-survey-confirmed").assertIsOff()
        node("osm-survey-review").assertIsNotEnabled()
        node("osm-survey-save").performClick()
        compose.runOnIdle {
            val value = requireNotNull(saved?.surveyObservation())
            assertEquals(13L, value.baseVersion)
            assertEquals("Mo-Su 10:00-17:00", value.hours.before)
            assertEquals(SurveyAction.UNTOUCHED, value.hours.action)
            assertFalse(value.hours.fullyObserved)
            assertFalse(requireNotNull(saved).surveyConfirmed)
        }
        node("osm-survey-opening_hours-VERIFY").performClick()
        node("osm-survey-confirmed").performClick()
        node("osm-survey-review").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(13L, reviewed?.surveyObservation()?.baseVersion) }
    }
}
