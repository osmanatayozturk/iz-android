package org.iz.navigation.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.iz.navigation.BuildConfig
import org.iz.navigation.IzApplication
import org.iz.navigation.data.*
import org.iz.navigation.integration.*
import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.iz.navigation.tracking.TrackerSettings
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.time.LocalDate
import java.util.UUID

/** Opt-in only on the separately allocated offline QA package. No publisher/auth replacement. */
@RunWith(AndroidJUnit4::class)
class OsmSurveyNativeRoutingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeMarkerDistanceListAndSavedPlaceReopenTheSameDiaryAppDraft() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit isolated survey acceptance opt-in required",
            args.getString("izSurveyNativeAcceptance") == "isolated-offline")
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID != "org.iz.navigation")
        check(args.getString("izExpectedPackage") == BuildConfig.APPLICATION_ID)
        check(args.getString("izCandidateCommit").orEmpty().matches(Regex("[0-9a-f]{40}")))
        val app = compose.activity.application as IzApplication
        val connectivity = checkNotNull(app.getSystemService(ConnectivityManager::class.java))
        check(connectivity.allNetworks.none {
            connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }) { "This bounded fixture requires an offline allocated QA package" }
        check(!TrackerSettings(app).enabled && !TrackingService.isRunning)
        val nav = app.navigation.state.value
        check(nav.sessionId == null && nav.journey == null && !nav.recording && !nav.guidance && !nav.simulation)
        val repository = DiaryRepository(app)
        val before = runBlocking { repository.snapshot() }
        check(before.journeys.none { it.endedAt == null || it.status == JourneyStatus.TEMPORARY })
        check(before.places.all { it.sortOrder < PlaceOrderRules.MAX_ORDER })
        val namespace = "survey-native-${UUID.randomUUID()}"
        val ref = OsmRef(OsmType.NODE, (UUID.randomUUID().mostSignificantBits and 0x0000FFFFFFFFFFFFL) + 1)
        check(before.places.none { it.osmType == ref.type && it.osmId == ref.id } &&
            before.mapEdits.none { it.targetType == ref.type && it.targetId == ref.id })
        val tags = mapOf("amenity" to "cafe", "name" to namespace,
            "opening_hours" to "Mo-Fr 09:00-18:00; PH off", "wheelchair" to "limited",
            "check_date:opening_hours" to "2020-01-02", "check_date:wheelchair" to "2021-03-04")
        val venue = SurveyVenue(ref, 12, namespace, 0.001, 0.002, tags)
        val target = OsmCorrectionElement(ref, 12, 99, null, "0.001", "0.002", emptyList(), tags)
        val snapshot = OsmSurveySnapshot(WeatherCoordinate(0.0, 0.0), listOf(venue),
            System.currentTimeMillis(), OsmServiceSettings(app).read().overpass, false)
        val place = Place(id = UUID.randomUUID().toString(), name = namespace, latitude = venue.latitude,
            longitude = venue.longitude, osmType = ref.type, osmId = ref.id, source = PlaceSource.OSM)
        val readRefs = mutableListOf<OsmRef>()
        val reads = OsmSurveyReads(cached = { snapshot }, nearby = { _, current ->
            check(current()); snapshot
        }, target = { requested -> check(requested == ref); readRefs += requested; target })
        var map: MapLibreMap? = null
        val presentation = OsmMapPresentation(style = ::offlineStyle, onReady = { map = it })
        val raw = "Mo 09:00-10:00; definitely-invalid"
        val date = LocalDate.now().minusDays(2).toString()
        try {
            runBlocking { repository.savePlace(place) }
            compose.setContent {
                CompositionLocalProvider(LocalOsmSurveyReads provides reads,
                    LocalOsmMapPresentation provides presentation) {
                    androidx.compose.material3.MaterialTheme { DiaryApp(null, {}) }
                }
            }
            compose.onNodeWithTag("home-menu").performClick()
            compose.onNodeWithTag("menu-osm").performScrollTo().performClick()
            compose.onNodeWithTag("osm-contribution-list").performScrollToNode(hasText("Yerinde tamamla"))
            compose.onNodeWithText("Yerinde tamamla").performClick()
            val row = "osm-survey-venue-${ref.type}-${ref.id}"
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag(row).assertIsDisplayed()
            // Wait for the actual native renderer, then send a physical touch through the Compose host.
            var pin = Offset.Zero
            compose.waitUntil(15_000) {
                var rendered = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    map?.let { native ->
                        val point = native.projection.toScreenLocation(LatLng(venue.latitude, venue.longitude))
                        val features = native.queryRenderedFeatures(point, "private-place-pins")
                        rendered = features.singleOrNull()?.getStringProperty("placeId") == "${ref.type}:${ref.id}"
                        pin = Offset(point.x, point.y)
                    }
                }
                rendered
            }
            compose.onNodeWithTag("osm-survey-native-map").performTouchInput { click(pin) }
            waitForEditor()
            editor("osm-survey-observed-on").performTextReplacement(date)
            editor("osm-survey-opening_hours-EDIT").performClick()
            editor("osm-survey-hours-advanced").performTextReplacement(raw)
            editor("osm-survey-wheelchair-UNKNOWN").performClick()
            editor("osm-survey-save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("osm-survey-editor-list").fetchSemanticsNodes().isEmpty() }
            val saved = runBlocking { repository.snapshot().mapEdits.single { it.targetType == ref.type && it.targetId == ref.id } }
            val observation = requireNotNull(saved.surveyObservation())
            assertEquals(raw, observation.hours.value)
            assertEquals(SurveyAction.EDIT, observation.hours.action)
            assertEquals(SurveyAction.UNKNOWN, observation.wheelchair.action)
            assertEquals(tags["opening_hours"], observation.hours.before)
            assertEquals(tags["check_date:opening_hours"], observation.hours.beforeDate)
            assertEquals(date, observation.observedOn)
            compose.onNodeWithText("Kapat").performClick() // actual map-edit page -> nearby
            compose.onNodeWithTag(row).performClick() // actual distance-list route
            assertReopened(raw, date)
            assertSameDraft(repository, saved, ref)
            editorClose()
            compose.onNodeWithText("Kapat").performClick()
            compose.onNodeWithText("Geri").performClick()
            compose.onNodeWithText("Kapat").performClick() // hub -> home
            compose.onNodeWithTag("home-menu").performClick()
            compose.onNodeWithTag("menu-places").performScrollTo().performClick()
            compose.onNodeWithTag("places-search").performTextReplacement(namespace)
            compose.onNodeWithTag("place-row-${place.id}").performScrollTo().performClick()
            compose.onNodeWithTag("osm-place-survey").performScrollTo().performClick()
            assertReopened(raw, date)
            assertSameDraft(repository, saved, ref)
            compose.runOnIdle { assertEquals(listOf(ref, ref, ref), readRefs) }
        } finally {
            // Dispose entry-owned coroutines first, then remove only this proven random namespace.
            compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
            compose.waitForIdle()
            runBlocking {
                repository.snapshot().mapEdits.filter { it.targetType == ref.type && it.targetId == ref.id }.forEach {
                    check(it.status == MapEditStatus.DRAFT && it.localAttemptId == null && !it.localChangesetCreated)
                    repository.deleteMapEditIfUnchanged(it)
                }
                if (repository.snapshot().places.any { it.id == place.id }) repository.deletePlace(place.id)
                assertEquals("Every pre-existing diary row must remain unchanged", before, repository.snapshot())
            }
        }
    }

    private fun waitForEditor() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("osm-survey-editor-list").fetchSemanticsNodes().isNotEmpty()
    }
    private fun editor(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("osm-survey-editor-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun assertReopened(raw: String, date: String) {
        waitForEditor()
        editor("osm-survey-observed-on").assertTextContains(date)
        editor("osm-survey-hours-advanced").assertTextContains(raw)
        editor("osm-survey-wheelchair-UNKNOWN").assertIsSelected()
    }
    private fun editorClose() {
        compose.onNodeWithTag("osm-survey-editor-list").performScrollToNode(hasText("Kapat"))
        compose.onNodeWithText("Kapat").performClick()
    }
    private fun assertSameDraft(repository: DiaryRepository, saved: MapEditDraft, ref: OsmRef) {
        val current = runBlocking { repository.snapshot().mapEdits.filter { it.targetType == ref.type && it.targetId == ref.id } }
        assertEquals(listOf(saved), current)
        assertNull(current.single().localAttemptId)
        assertEquals(MapEditStatus.DRAFT, current.single().status)
    }

    private fun offlineStyle(value: String): String = JSONObject(value).apply {
        val sources = getJSONObject("sources")
        sources.remove("osm-tiles")
        val original = getJSONArray("layers")
        put("layers", JSONArray().apply {
            for (i in 0 until original.length()) {
                val layer = original.getJSONObject(i)
                if (layer.optString("source") != "osm-tiles" && layer.optString("type") != "symbol") put(layer)
            }
        })
        remove("glyphs"); remove("sprite")
    }.toString()
}
