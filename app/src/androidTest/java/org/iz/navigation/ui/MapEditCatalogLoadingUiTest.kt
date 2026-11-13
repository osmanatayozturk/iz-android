package org.iz.navigation.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.iz.navigation.data.*
import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.iz.navigation.integration.osm.editorDraft
import org.iz.navigation.osmcatalog.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises the production loading owner and forms with bounded, controlled asset IO. */
@RunWith(AndroidJUnit4::class)
class MapEditCatalogLoadingUiTest {
    @get:Rule val compose = createComposeRule()
    private val pending = mutableListOf<CompletableDeferred<OsmPresetCatalog>>()
    private fun deferred() = CompletableDeferred<OsmPresetCatalog>().also { pending += it }
    private fun readCatalog() = OsmPresetCatalog.load(ApplicationProvider.getApplicationContext<Context>()
        .assets.open(OsmPresetCatalog.BUNDLED_ASSET))
    private val catalog by lazy { readCatalog() }

    @After fun releasePendingLoads() {
        pending.forEach { it.completeExceptionally(CancellationException("Test finished")) }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun loadingToReadyPreservesTypedFieldsConsentAndBlankRequiredType() {
        val result = deferred()
        val fixture = catalog
        val initial = newMapEditDraft(41.0, 29.0)
        var baseline by mutableStateOf(initial)
        var saved: MapEditDraft? = null
        var closed = 0
        var reads = 0
        val loader: suspend () -> OsmPresetCatalog = { reads++; result.await() }
        compose.setContent { MaterialTheme {
            val load = rememberMapEditCatalog(loader)
            MapEditEditor(initial, false, true, false, null, { closed++ },
                { saved = it; baseline = it }, { error("No type selected") }, {}, { error("Unexpected write") },
                liveBaseline = { baseline }, catalogLoad = load)
        } }
        compose.onNodeWithTag("osm-map-catalog-loading").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("osm-map-type").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("osm-map-name").performScrollTo().performTextReplacement("Korunan ad")
        compose.onNodeWithTag("osm-map-phone").performScrollTo().performTextReplacement("+90 312 555 01 23")
        compose.onNodeWithTag("osm-map-survey").performScrollTo().performClick()
        compose.runOnIdle { result.complete(fixture) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("osm-map-catalog-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("osm-map-type").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Tür seç").assertIsDisplayed()
        compose.onNodeWithTag("osm-map-name").performScrollTo().assertTextContains("Korunan ad")
        compose.onNodeWithTag("osm-map-phone").performScrollTo().assertTextContains("+90 312 555 01 23")
        compose.onNodeWithTag("osm-map-survey").performScrollTo().assertIsOn()
        compose.onNodeWithText("Önizle").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Taslağı sakla").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, reads)
            assertEquals("Korunan ad", saved?.name)
            assertEquals("+90 312 555 01 23", saved?.phone)
            assertNull(saved?.presetSnapshot)
            assertEquals(true, saved?.surveyConfirmed)
        }
        compose.onNodeWithText("Kapat").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun errorRetryReturnsToPendingThenReadyWithoutChangingFrozenRulesOrFavorites() {
        val first = deferred()
        val second = deferred()
        val fixture = catalog
        val frozen = fixture.snapshot("amenity/drinking_water", OsmPresetGeometry.POINT).let {
            it.copy(ruleJson = org.json.JSONObject(it.ruleJson).put("name", "Dondurulmuş tür").toString())
        }
        val initial = newMapEditDraft(41.0, 29.0, "Korunan ad").selectPreset(frozen)
            .copy(phone = "+90 312 555 01 23", phoneAction = MapEditFieldAction.SET, surveyConfirmed = true)
        var saved: MapEditDraft? = null
        var reads = 0
        val loader: suspend () -> OsmPresetCatalog = { if (++reads == 1) first.await() else second.await() }
        compose.setContent { MaterialTheme {
            val load = rememberMapEditCatalog(loader)
            MapEditEditor(initial, false, true, false, null, {}, { saved = it },
                { it.editableCopy() }, {}, { error("Unexpected write") },
                favoriteIds = setOf("amenity/cafe"), onFavorite = { _, _ -> error("Retry must not change favorites") },
                catalogLoad = load)
        } }
        compose.runOnIdle { first.completeExceptionally(IllegalStateException("Controlled read failure")) }
        awaitText("Tür kataloğu yüklenemedi.")
        compose.onNodeWithTag("osm-map-catalog-retry").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-catalog-loading").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tür kataloğu yüklenemedi.").assertDoesNotExist()
        compose.onNodeWithText("Taslağı sakla").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Kapat").performScrollTo().assertIsEnabled()
        compose.runOnIdle { second.complete(fixture) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("osm-map-catalog-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("osm-map-survey").performScrollTo().assertIsOn()
        compose.onNodeWithText("Taslağı sakla").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(initial, saved); assertEquals(2, reads) }
        compose.onNodeWithTag("osm-map-type").performScrollTo().assertTextContains("Dondurulmuş tür").performClick()
        compose.onNodeWithText("Tür ara").performTextInput("amenity=cafe")
        val label = requireNotNull(fixture.resolve("amenity/cafe")).name
        compose.onNodeWithContentDescription("$label favorilerden çıkar").assertIsOn()
    }

    @Test fun explicitCatalogFixtureIsReadyEvenWhenAnInheritedLoaderHasFailed() {
        val fixture = catalog
        val initial = newMapEditDraft(41.0, 29.0)
        var retries = 0
        val failed = MapEditCatalogLoad(Result.failure(IllegalStateException("Unused source"))) { retries++ }
        compose.setContent { MaterialTheme {
            MapEditEditor(initial, false, true, false, null, {}, {}, { error("No type") }, {},
                { error("Unexpected write") }, catalog = fixture, catalogLoad = failed)
        } }
        compose.onNodeWithTag("osm-map-type").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("osm-map-catalog-loading").assertDoesNotExist()
        compose.onNodeWithTag("osm-map-catalog-retry").assertDoesNotExist()
        compose.onNodeWithText("Tür seç").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, retries) }
    }

    @Test fun canceledStaleSuccessCannotReplaceANewerPendingAttempt() {
        assertStaleCompletionIgnored(fail = false)
    }

    @Test fun canceledStaleFailureCannotTurnANewerPendingAttemptIntoError() {
        assertStaleCompletionIgnored(fail = true)
    }

    private fun assertStaleCompletionIgnored(fail: Boolean) {
        val first = deferred()
        val second = deferred()
        val oldFinished = CompletableDeferred<Unit>()
        val stale = readCatalog()
        val fresh = catalog
        var reads = 0
        var state: MapEditCatalogLoad? = null
        val loader: suspend () -> OsmPresetCatalog = {
            if (++reads == 1) withContext(NonCancellable) {
                try { first.await() } finally { oldFinished.complete(Unit) }
            } else second.await()
        }
        compose.setContent { MaterialTheme {
            val load = rememberMapEditCatalog(loader)
            SideEffect { state = load }
            MapEditCatalogStatus(load, true)
            if (load.result?.isSuccess == true) Text("Ready")
        } }
        compose.waitUntil(5_000) { compose.runOnIdle { reads == 1 } }
        compose.runOnIdle { requireNotNull(state).retry() }
        compose.waitUntil(5_000) { compose.runOnIdle { reads == 2 } }
        compose.runOnIdle {
            if (fail) first.completeExceptionally(IllegalStateException("Canceled old failure")) else first.complete(stale)
        }
        compose.waitUntil(5_000) { oldFinished.isCompleted }
        compose.onNodeWithTag("osm-map-catalog-loading").assertIsDisplayed()
        compose.onNodeWithText("Tür kataloğu yüklenemedi.").assertDoesNotExist()
        compose.onNodeWithText("Ready").assertDoesNotExist()
        compose.runOnIdle { assertNull(requireNotNull(state).result); second.complete(fresh) }
        awaitText("Ready")
        compose.runOnIdle { assertSame(fresh, requireNotNull(state).result!!.getOrThrow()); assertEquals(2, reads) }
    }

    @Test fun correctionRetryPreservesSetDeleteAndObservationIntentForLocalSave() {
        val first = deferred()
        val second = deferred()
        val fixture = catalog
        val target = OsmCorrectionElement(OsmRef(OsmType.NODE, 901), 3, 99, 30,
            "41.0", "29.0", emptyList(), mapOf("amenity" to "cafe", "name" to "Eski ad", "phone" to "0312 555 01 23"))
        val initial = target.editorDraft(fixture, UUID.randomUUID().toString(), 41.0, 29.0).copy(
            name = "Yerel ad", nameAction = MapEditFieldAction.SET, phone = "", phoneAction = MapEditFieldAction.DELETE,
            surveyConfirmed = true)
        var reads = 0
        var saved: MapEditDraft? = null
        val loader: suspend () -> OsmPresetCatalog = { if (++reads == 1) first.await() else second.await() }
        compose.setContent { MaterialTheme {
            val load = rememberMapEditCatalog(loader)
            MapEditCorrectionEditor(initial, target, false, false, false, null,
                { initial }, { false }, {}, { saved = it }, { error("Target supplied") },
                { error("Loading must not prepare a review") }, { false }, {},
                { error("Unexpected write") }, {}, catalogLoad = load)
        } }
        compose.runOnIdle { first.completeExceptionally(IllegalStateException("Controlled failure")) }
        awaitText("Tür kataloğu yüklenemedi.")
        compose.onNodeWithText("OSM düzenleme iznini etkinleştir").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Taslağı sakla").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(initial, saved) }
        compose.onNodeWithTag("osm-map-catalog-retry").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-catalog-loading").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { second.complete(fixture) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("osm-map-catalog-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("osm-map-correction-name").performScrollTo().assertTextContains("Yerel ad")
        compose.onNodeWithText("OSM düzenleme iznini etkinleştir").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("osm-map-correction-survey").performScrollTo().assertIsOn()
        compose.onNodeWithText("Taslağı sakla").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(initial, saved); assertEquals(2, reads) }
    }
}
