package org.iz.navigation.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.launch
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditFieldAction
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.data.selectedPresetSnapshot
import org.iz.navigation.integration.parseOverpassPlaces
import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.iz.navigation.integration.osm.OsmCorrectionReview
import org.iz.navigation.integration.osm.OsmCredentials
import org.iz.navigation.integration.osm.OsmUnsupportedCorrection
import org.iz.navigation.integration.osm.OsmUser
import org.iz.navigation.integration.osm.editorDraft
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real parser/catalog/owner/factory/editor/review; injected discovery/target IO and URL sink only.
 * This is not native-map hit testing, live Overpass/API validation, or a public write.
 */
@RunWith(AndroidJUnit4::class)
class OsmLightEditorDiscoveryFlowTest {
    @get:Rule val compose = createComposeRule()
    private val catalog by lazy {
        OsmPresetCatalog.load(ApplicationProvider.getApplicationContext<Context>().assets.open(OsmPresetCatalog.BUNDLED_ASSET))
    }
    private val credentials = OsmCredentials("synthetic-token", OsmUser(41, "Synthetic mapper"), null, setOf("write_api"))
    private lateinit var owner: PlaceInformationDiscoveryOwner
    private val active = mutableStateOf<Entry?>(null)
    private val selections = mutableListOf<SelectedOsmPlace>()
    private val targetReads = mutableListOf<OsmRef>()
    private val factoryDrafts = mutableListOf<MapEditDraft>()
    private val rejectedFactories = mutableListOf<OsmRef>()
    private val reviews = mutableListOf<OsmCorrectionReview>()
    private val externalIntents = mutableListOf<Intent>()
    private var nearbyReads = 0

    @Test fun unnamedBuildingWayRequiresExplicitChoiceAndReviewsNamePhoneWithoutChangingItsArea() {
        val building = building()
        show(SUPPORTED_DISCOVERY, listOf(tree(), building))
        choose(OsmRef(OsmType.WAY, 804), expectedIndex = 1)
        compose.runOnIdle {
            val selected = selections.single()
            assertEquals(catalog.resolve("building")!!.name, selected.name)
            val draft = factoryDrafts.single()
            assertEquals("", draft.name) // The parser's fallback label is never a public name.
            assertEquals("building", draft.presetId)
            assertEquals(OsmPresetGeometry.AREA, draft.selectedPresetSnapshot()!!.geometry)
            assertEquals("111; 222", draft.phone)
            assertEquals(MapEditFieldAction.UNTOUCHED, draft.nameAction)
            assertEquals(MapEditFieldAction.UNTOUCHED, draft.phoneAction)
            assertTrue(rejectedFactories.isEmpty())
        }
        editAndReview(building, "Synthetic building", "+90 312 000 00 01")
    }

    @Test fun nonLegacyNaturalTreePointKeepsItsActualRefCoordinatesAndUnrelatedTagsThroughReview() {
        val tree = tree()
        show(SUPPORTED_DISCOVERY, listOf(tree, building()))
        choose(OsmRef(OsmType.NODE, 804), expectedIndex = 0)
        compose.runOnIdle {
            assertEquals(catalog.resolve("natural/tree")!!.name, selections.single().name)
            val draft = factoryDrafts.single()
            assertEquals("natural/tree", draft.presetId)
            assertEquals(OsmPresetGeometry.POINT, draft.selectedPresetSnapshot()!!.geometry)
            assertEquals("", draft.name)
            assertEquals("333", draft.phone)
            assertTrue(rejectedFactories.isEmpty())
        }
        editAndReview(tree, "Synthetic tree", "+90 312 000 00 02")
    }

    @Test fun unsupportedClosedHighwayKeepsSelectedIdentityAndRoutesToExternalEditorWithoutReview() {
        val highway = OsmCorrectionElement(OsmRef(OsmType.WAY, 909), 8, 90, 41,
            null, null, listOf(70L, 12L, 33L, 70L), mapOf("highway" to "residential", "name" to "Closed highway"))
        show(UNSUPPORTED_DISCOVERY, listOf(tree(), highway))
        choose(highway.ref, expectedIndex = 1)
        compose.onNodeWithText("Bu OSM nesnesinin geometrisi İz'de düzenlenemiyor. OSM düzenleyicisini kullan.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("osm-map-correction-name").assertDoesNotExist()
        compose.onNodeWithTag("osm-map-correction-phone").assertDoesNotExist()
        compose.onNodeWithText("OSM bilgilerini kontrol et ve incele").assertDoesNotExist()
        compose.onNodeWithText("OSM düzenleyicisinde aç").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(highway.ref), rejectedFactories)
            assertTrue(factoryDrafts.isEmpty())
            assertTrue(reviews.isEmpty())
            assertEquals(listOf(highway.ref), targetReads)
            val intent = externalIntents.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://www.openstreetmap.org/edit?editor=id&way=909", intent.dataString)
        }
    }

    private fun show(json: String, elements: List<OsmCorrectionElement>) {
        val actualCatalog = catalog
        val targets = elements.associateBy { it.ref }
        compose.setContent {
            val baseContext = LocalContext.current
            val urlSink = remember(baseContext) {
                object : ContextWrapper(baseContext) {
                    override fun startActivity(intent: Intent) { externalIntents += Intent(intent) }
                }
            }
            CompositionLocalProvider(LocalContext provides urlSink) { MaterialTheme {
                val scope = rememberCoroutineScope()
                owner = remember {
                    PlaceInformationDiscoveryOwner(scope,
                        nearby = { latitude, longitude ->
                            assertEquals(39.93, latitude, 0.0)
                            assertEquals(32.85, longitude, 0.0)
                            nearbyReads++
                            parseOverpassPlaces(json, actualCatalog)
                        },
                        onSelect = { selected ->
                            selections += selected
                            scope.launch {
                                val ref = requireNotNull(selected.osmRef)
                                val actual = readTarget(ref, targets)
                                val draft = try {
                                    actual.editorDraft(actualCatalog, UUID.randomUUID().toString(), selected.latitude, selected.longitude)
                                        .also { factoryDrafts += it }
                                } catch (_: OsmUnsupportedCorrection) {
                                    rejectedFactories += ref
                                    // A routing shell only: the real factory rejected this geometry.
                                    // The real editor must independently reject it and offer its fallback.
                                    MapEditDraft(latitude = selected.latitude, longitude = selected.longitude,
                                        editorVersion = 2, targetType = ref.type, targetId = ref.id)
                                }
                                active.value = Entry(draft, actual)
                            }
                        }, identity = { "synthetic-discovery-flow" })
                }
                PlaceInformationDiscoveryContent(owner)
                active.value?.let { entry ->
                    MapEditCorrectionEditor(
                        initial = entry.draft, target = entry.element, busy = false, canWriteMap = true,
                        authBusy = false, authError = null, liveBaseline = { entry.draft }, liveBusy = { false },
                        onClose = { error("No close action expected") }, onSave = { error("No save action expected") },
                        onReadTarget = { readTarget(it, targets) },
                        onReview = { draft ->
                            val ref = OsmRef(requireNotNull(draft.targetType), requireNotNull(draft.targetId))
                            readTarget(ref, targets).review(draft, credentials).also { reviews += it }
                        },
                        isReviewCurrent = { it.credentials === credentials && it.original.ref == selections.singleOrNull()?.osmRef },
                        onLogin = { error("Synthetic review must not log in") },
                        onPublish = { error("Preview must never publish") },
                        onNoteFallback = { error("No note action expected") }, catalog = actualCatalog,
                    )
                }
            } }
        }
    }

    private fun choose(ref: OsmRef, expectedIndex: Int) {
        compose.runOnIdle {
            assertEquals(0, nearbyReads)
            assertTrue(selections.isEmpty())
            assertTrue(targetReads.isEmpty())
            owner.tap(39.93, 32.85, 17.0)
        }
        compose.onNodeWithTag("place-info-discovery-choice-$expectedIndex").assertIsDisplayed()
            .assertTextContains("${ref.type.name.lowercase()}/${ref.id}", substring = true)
        compose.onNodeWithTag("osm-map-correction-name").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, nearbyReads)
            assertEquals(2, owner.state.choices.size)
            assertEquals(ref, owner.state.choices[expectedIndex].osmRef)
            assertTrue(selections.isEmpty()) // Multiple candidates never auto-select the first.
            assertTrue(targetReads.isEmpty())
        }
        compose.onNodeWithTag("place-info-discovery-choice-$expectedIndex").performClick()
        compose.runOnIdle {
            assertEquals(listOf(ref), selections.map { it.osmRef })
            assertEquals(listOf(ref), targetReads)
            assertTrue(owner.state.choices.isEmpty())
        }
    }

    private fun editAndReview(original: OsmCorrectionElement, name: String, phone: String) {
        compose.onNodeWithTag("osm-map-correction-name").performScrollTo().performTextReplacement(name)
        compose.onNodeWithTag("osm-map-correction-phone").performScrollTo().performTextReplacement(phone)
        val preview = compose.onNodeWithText("OSM bilgilerini kontrol et ve incele")
        preview.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("osm-map-correction-survey").performScrollTo().assertIsOff().performClick().assertIsOn()
        preview.performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Sonra: $name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sonra: $phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("contact:phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("osm-map-correction-publish").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            val review = reviews.single()
            assertEquals(original, review.original)
            assertEquals(original.copy(tags = original.tags - "contact:phone" + mapOf("name" to name, "phone" to phone)), review.corrected)
            assertEquals(
                mapOf("name" to (original.tags["name"] to name), "phone" to (original.tags["phone"] to phone),
                    "contact:phone" to (original.tags["contact:phone"] to null)),
                review.fields.associate { it.key to (it.before to it.after) },
            )
            assertEquals(MapEditFieldAction.SET, review.draft.nameAction)
            assertEquals(MapEditFieldAction.SET, review.draft.phoneAction)
            assertFalse(review.draft.typeChanged)
            assertEquals(review.draft.originalPresetSnapshot, review.draft.presetSnapshot)
            assertEquals(listOf(original.ref, original.ref), targetReads)
            assertEquals(1, nearbyReads)
            assertEquals(1, selections.size)
            assertTrue(externalIntents.isEmpty())
        }
    }

    private suspend fun readTarget(ref: OsmRef, targets: Map<OsmRef, OsmCorrectionElement>): OsmCorrectionElement {
        targetReads += ref
        return requireNotNull(targets[ref]) { "Unexpected synthetic target identity: $ref" }
    }

    private fun building() = OsmCorrectionElement(OsmRef(OsmType.WAY, 804), 7, 90, 41,
        null, null, listOf(91L, 17L, 44L, 8L, 91L),
        mapOf("building" to "yes", "addr:street" to "Keep building street", "addr:housenumber" to "12A",
            "operator" to "Keep building operator", "phone" to "111", "contact:phone" to "222"))

    private fun tree() = OsmCorrectionElement(OsmRef(OsmType.NODE, 804), 9, 91, 41,
        "39.9300001", "32.8500002", emptyList(),
        mapOf("natural" to "tree", "source" to "survey", "operator" to "Keep tree operator", "contact:phone" to "333"))

    private data class Entry(val draft: MapEditDraft, val element: OsmCorrectionElement)

    private companion object {
        val SUPPORTED_DISCOVERY = """{"elements":[
            {"type":"node","id":804,"lat":39.9300001,"lon":32.8500002,"tags":{"natural":"tree","contact:phone":"333","source":"survey"}},
            {"type":"way","id":804,"center":{"lat":39.93,"lon":32.85},"tags":{"building":"yes","addr:street":"Keep building street"}}
        ]}"""
        val UNSUPPORTED_DISCOVERY = """{"elements":[
            {"type":"node","id":804,"lat":39.9300001,"lon":32.8500002,"tags":{"natural":"tree"}},
            {"type":"way","id":909,"center":{"lat":39.93,"lon":32.85},"tags":{"highway":"residential","name":"Closed highway"}}
        ]}"""
    }
}
