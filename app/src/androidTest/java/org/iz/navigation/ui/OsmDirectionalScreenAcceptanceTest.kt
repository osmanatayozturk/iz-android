package org.iz.navigation.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.iz.navigation.MainActivity
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.OsmDirectedPoint
import org.iz.navigation.integration.OsmGpxPreparation
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.integration.prepareOsmGpxTrace
import org.iz.navigation.integration.osm.OSM_GPX_READ_SCOPE
import org.iz.navigation.integration.osm.OSM_GPX_WRITE_SCOPE
import org.iz.navigation.integration.osm.OsmAuthUiState
import org.iz.navigation.integration.osm.OsmGpxController
import org.iz.navigation.integration.osm.OsmGpxGateway
import org.iz.navigation.integration.osm.OsmGpxHistoryEntry
import org.iz.navigation.integration.osm.OsmGpxHistoryGateway
import org.iz.navigation.integration.osm.OsmGpxRemoteData
import org.iz.navigation.integration.osm.OsmGpxUploadResult
import org.iz.navigation.integration.osm.OsmGpxVisibility
import org.iz.navigation.integration.osm.OsmUser
import org.iz.navigation.osmcommunity.CommunitySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmDirectionalScreenAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun candidatesHideTenSameDirectionRidesAndPostThePreviewedReverseOnly() {
        val base = ride("remote-source", 0)
        val repeats = (1..10).map { ride("same-direction-$it", it) }
        val opposite = ride("opposite-direction", 12, reverse = true)
        val session = fixtureSession(271_260_001L, "QA directions")
        val api = FixtureOsm(session)
        api.remote(301L, "from-another-app.gpx", base.prepared)
        withSamples(repeats + opposite, api) {
            openGps()
            val list = compose.onNodeWithTag("osm-gps-list")
            waitForTag("osm-gps-journey-${opposite.journey.id}")
            for (ride in repeats) {
                val target = hasTestTag("osm-gps-journey-${ride.journey.id}")
                assertTrue("The whole candidate list must exclude ${ride.journey.id}",
                    runCatching { list.performScrollToNode(target) }.exceptionOrNull() is AssertionError)
            }
            assertTrue(api.downloaded.contains(301L))
            assertTrue(api.sent.isEmpty())
            list.performScrollToNode(hasTestTag("osm-gps-journey-${opposite.journey.id}"))
            compose.onNodeWithTag("osm-gps-journey-${opposite.journey.id}").assertIsOff().performClick().assertIsOn()
            awaitReview().performClick()
            waitForTag("osm-gps-consent-confirm")
            val preview = compose.onNodeWithTag("osm-gps-preview-${opposite.journey.id}")
            preview.assert(SemanticsMatcher.expectValue(OsmGpsPreparedSha256, opposite.prepared.sha256))
            preview.assert(SemanticsMatcher.expectValue(OsmGpsPreparedPointCount, opposite.prepared.pointCount))
            compose.onNodeWithTag("osm-gps-visibility-identifiable").assertDoesNotExist()
            compose.onNodeWithTag("osm-gps-consent-confirm").performClick()
            compose.waitUntil(12_000) { api.sent.size == 1 }
            val sent = api.sent.single()
            assertEquals(OsmGpxVisibility.TRACKABLE, sent.second)
            assertEquals(opposite.prepared.gpxXml, sent.first.gpxXml)
            assertEquals(opposite.prepared.segments, sent.first.segments)
            assertFalse(sent.first.segments.flatten().any { it.recordedAt == opposite.points.first().recordedAt })
            assertFalse(sent.first.segments.flatten().any { it.recordedAt == opposite.points.last().recordedAt })
        }
    }

    @Test fun batchChangesRequireNewPreviewBeforePostingOnlyNewSegments() {
        val first = ride("batch-oldest", 20)
        val detour = ride("batch-detour", 21, detour = true)
        val api = FixtureOsm(fixtureSession(271_260_002L, "QA batch"))
        withSamples(listOf(first, detour), api) {
            openGps()
            waitForTag("osm-gps-journey-${first.journey.id}")
            compose.onNodeWithTag("osm-gps-select-all").performClick()
            awaitReview().performClick()
            waitForTag("osm-gps-preview-${first.journey.id}")
            compose.onNodeWithTag("osm-gps-preview-${first.journey.id}")
                .assert(SemanticsMatcher.expectValue(OsmGpsPreparedSha256, first.prepared.sha256))
            compose.onNodeWithTag("osm-gps-preview-next").performScrollTo().performClick()
            waitForTag("osm-gps-preview-${detour.journey.id}")
            val presentedHash = previewHash(detour)
            assertNotEquals(detour.prepared.sha256, presentedHash)

            api.remote(477L, "other-app-added.gpx", first.prepared)
            val previousReads = api.listReads.get()
            compose.onNodeWithTag("osm-gps-consent-confirm").performClick()
            compose.waitUntil(12_000) { api.listReads.get() > previousReads }
            compose.waitUntil(12_000) {
                compose.onAllNodesWithTag("osm-gps-journey-${first.journey.id}").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithTag("osm-gps-journey-${detour.journey.id}").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("A changed plan needs new consent before dispatch", api.sent.isEmpty())
            val detourRow = compose.onNodeWithTag("osm-gps-journey-${detour.journey.id}")
            if (detourRow.fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState)
                != androidx.compose.ui.state.ToggleableState.On) detourRow.performClick()
            awaitReview().performClick()
            waitForTag("osm-gps-preview-${detour.journey.id}")
            val finalHash = previewHash(detour)
            assertEquals(presentedHash, finalHash)
            compose.onNodeWithTag("osm-gps-consent-confirm").performClick()
            compose.waitUntil(12_000) { api.sent.size == 1 }
            val posted = api.sent.single()
            assertEquals(OsmGpxVisibility.TRACKABLE, posted.second)
            assertEquals(detour.journey.id, posted.first.journeyId)
            assertEquals(finalHash, posted.first.sha256)
            val removed = detour.points[40]
            assertTrue(detour.prepared.segments.flatten().any { it.recordedAt == removed.recordedAt })
            assertFalse(Instant.ofEpochMilli(removed.recordedAt).toString() in posted.first.gpxXml)
            assertFalse(posted.first.segments.flatten().any { it.recordedAt == removed.recordedAt })
            assertTrue(posted.first.segments.flatten().any { it.latitude > .031 })
        }
    }

    @Test fun largeRemoteHistoryMustBeCompleteBeforePlanAndConsentClearsWhenAccountChanges() {
        val opposite = ride("retry-opposite", 31, reverse = true)
        val api = FixtureOsm(fixtureSession(271_260_003L, "QA before switch"))
        api.largeRemote(511L, latitude = 35.0)
        api.largeRemote(512L, latitude = -30.0)
        api.failData = 511L
        withSamples(listOf(opposite), api) {
            openGps()
            waitForTag("osm-gps-coverage-issue", timeout = 30_000L)
            compose.onNodeWithTag("osm-gps-coverage-issue")
                .assertTextContains("Hesaptaki iz verisinin tamamı güvenle okunamadı", substring = true)
            compose.onNodeWithTag("osm-gps-journey-${opposite.journey.id}").assertDoesNotExist()
            assertTrue(api.sent.isEmpty())
            assertEquals(setOf(511L, 512L), api.downloaded.toSet())
            api.failData = null
            compose.onNodeWithTag("osm-gps-refresh").assertIsEnabled().performClick()
            waitForTag("osm-gps-journey-${opposite.journey.id}", timeout = 35_000L)
            assertEquals(300_002, api.remotePointCounts.values.sum())
            compose.onNodeWithTag("osm-gps-journey-${opposite.journey.id}").performClick()
            awaitReview().performClick()
            waitForTag("osm-gps-consent-confirm")
            compose.runOnIdle { api.switchAccount(fixtureSession(271_260_004L, "QA after switch", generation = 2)) }
            compose.waitUntil(12_000) {
                compose.onAllNodesWithTag("osm-gps-consent-confirm").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText("QA after switch").fetchSemanticsNodes().isNotEmpty()
            }
            waitForTag("osm-gps-journey-${opposite.journey.id}")
            compose.onNodeWithTag("osm-gps-journey-${opposite.journey.id}").assertIsOff()
            compose.onNodeWithTag("osm-gps-review").assertIsNotEnabled()
            assertTrue(api.sent.isEmpty())
        }
    }

    @Test fun onlySelectedLegacyTracesAreUpdatedAndResultsStaySeparateWhenOneFails() {
        val base = ride("visibility-source", 40)
        val api = FixtureOsm(fixtureSession(271_260_005L, "QA visibility"))
        api.remote(620L, "first-legacy.gpx", base.prepared, OsmGpxVisibility.IDENTIFIABLE)
        api.remote(610L, "second-legacy.gpx", base.prepared, OsmGpxVisibility.IDENTIFIABLE)
        api.remote(600L, "already-trackable.gpx", base.prepared, OsmGpxVisibility.TRACKABLE)
        api.remote(590L, "unselected-legacy.gpx", base.prepared, OsmGpxVisibility.IDENTIFIABLE)
        api.failUpdate = 610L
        withSamples(emptyList(), api) {
            openGps()
            compose.onNodeWithTag("osm-gps-page-history").performClick()
            waitForTag("osm-gps-history-list")
            val list = compose.onNodeWithTag("osm-gps-history-list")
            for (id in listOf(620L, 610L)) {
                list.performScrollToNode(hasTestTag("osm-gps-visibility-select-$id"))
                compose.onNodeWithTag("osm-gps-visibility-select-$id").assertIsOff().performClick().assertIsOn()
            }
            list.performScrollToNode(hasTestTag("osm-gps-history-600"))
            compose.onNodeWithTag("osm-gps-visibility-select-600").assertDoesNotExist()
            compose.onNodeWithTag("osm-gps-visibility-review").assertIsEnabled().performClick()
            waitForTag("osm-gps-visibility-consent")
            assertTrue(api.changed.isEmpty())
            compose.onNodeWithTag("osm-gps-visibility-confirm").performClick()
            compose.waitUntil(12_000L) { api.changed.size == 2 }
            for (id in listOf(620L, 610L)) {
                val tag = "osm-gps-visibility-result-$id"
                val text = if (id == 620L) "doğrulandı" else "doğrulanamadı"
                list.performScrollToNode(hasTestTag(tag))
                compose.waitUntil(12_000L) {
                    compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = true))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag(tag).assertIsDisplayed()
            }
            assertEquals(setOf(620L, 610L), api.changed.toSet())
            assertEquals(OsmGpxVisibility.TRACKABLE.wireValue, api.visibility(620L))
            assertEquals(OsmGpxVisibility.IDENTIFIABLE.wireValue, api.visibility(610L))
            assertEquals(OsmGpxVisibility.TRACKABLE.wireValue, api.visibility(600L))
            assertEquals(OsmGpxVisibility.IDENTIFIABLE.wireValue, api.visibility(590L))
            assertTrue(api.sent.isEmpty())
        }
    }

    private fun previewHash(sample: FixtureRide) =
        compose.onNodeWithTag("osm-gps-preview-${sample.journey.id}").fetchSemanticsNode().config[OsmGpsPreparedSha256]

    private fun openGps() {
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-osm").performScrollTo().performClick()
        compose.onNodeWithTag("osm-contribution-gps").performScrollTo().performClick()
        compose.onNodeWithTag("osm-gps-screen").assertIsDisplayed()
    }

    private fun awaitReview(): SemanticsNodeInteraction {
        val tag = "osm-gps-review"
        compose.waitUntil(12_000L) {
            val enabled = hasTestTag(tag) and isEnabled()
            compose.onAllNodes(enabled).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNodeWithTag(tag)
    }

    private fun waitForTag(tag: String, timeout: Long = 12_000L) {
        compose.waitUntil(timeout) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun withSamples(samples: List<FixtureRide>, api: FixtureOsm, use: () -> Unit) {
        val context = requireDirectionalQa()
        val diary = DiaryRepository(context)
        val installation = OsmGpxController.installForTesting(context, api.auth, api.sessions, api.upload,
            requestPermission = { error("Synthetic fixture must not open OAuth") }, historyGateway = api)
        try {
            runBlocking(Dispatchers.IO) {
                for (sample in samples) {
                    diary.saveJourney(sample.journey.copy(endedAt = null))
                    for (point in sample.points) diary.addPoint(point)
                    diary.finishJourney(sample.journey.id, sample.journey.endedAt!!)
                }
            }
            use()
        } finally {
            installation.close()
            runBlocking(Dispatchers.IO) {
                for (sample in samples) diary.deleteJourney(sample.journey.id)
            }
        }
    }
}

internal fun requireDirectionalQa(): Context {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("org.iz.navigation.qaosmyon20261127", context.packageName)
    assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
    assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
    assertEquals(0, Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON))
    @Suppress("DEPRECATION")
    val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions.orEmpty()
    assertTrue(Manifest.permission.INTERNET !in permissions)
    return context
}

private data class FixtureRide(val journey: Journey, val points: List<TrackPoint>) {
    val prepared get() = (prepareOsmGpxTrace(journey, points) as OsmGpxPreparation.Ready).trace
}

private fun ride(id: String, order: Int, reverse: Boolean = false, detour: Boolean = false): FixtureRide {
    val at = 1_740_000_000_000L + order * 3_600_000L
    val journey = Journey(id = "qa-direction-$id", title = "QA $id", startedAt = at, endedAt = at + 600_000L)
    val points = (0..130).map { i ->
        val index = if (reverse) 130 - i else i
        val delta = if (detour && index in 50..80) (.003 - kotlin.math.abs(index - 65) * .0002) else 0.0
        TrackPoint(id = "${journey.id}-$i", journeyId = journey.id, latitude = .03 + delta,
            longitude = -.35 + index * .00027, recordedAt = at + i * 4_000L, accuracy = 3f, speed = 10f)
    }
    return FixtureRide(journey, points)
}

private fun fixtureSession(id: Long, label: String, generation: Long = 1) =
    CommunitySession("synthetic-$id", id, label, setOf(OSM_GPX_READ_SCOPE, OSM_GPX_WRITE_SCOPE), generation)

private class FixtureOsm(session: CommunitySession) : OsmGpxHistoryGateway {
    val sessions = MutableStateFlow<CommunitySession?>(session)
    val auth = MutableStateFlow(auth(session))
    val downloaded = CopyOnWriteArrayList<Long>()
    val sent = CopyOnWriteArrayList<Pair<PreparedOsmGpxTrace, OsmGpxVisibility>>()
    val listReads = AtomicInteger(0)
    private val entries = CopyOnWriteArrayList<Pair<Long, OsmGpxHistoryEntry>>()
    private val traces = java.util.concurrent.ConcurrentHashMap<Long, PreparedOsmGpxTrace>()
    private val largeLatitude = java.util.concurrent.ConcurrentHashMap<Long, Double>()
    val remotePointCounts = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private val nextId = AtomicInteger(800)
    val changed = CopyOnWriteArrayList<Long>()
    @Volatile var failData: Long? = null
    @Volatile var failUpdate: Long? = null

    val upload = OsmGpxGateway { token, trace, visibility, canDispatch ->
        assertEquals(sessions.value?.token, token)
        assertTrue(canDispatch())
        val id = nextId.incrementAndGet().toLong()
        remote(id, trace.fileName, trace, visibility)
        sent += trace to visibility
        OsmGpxUploadResult.Received(id)
    }

    override suspend fun list(token: String, expectedUserId: Long): List<OsmGpxHistoryEntry> {
        assertEquals(sessions.value?.token, token)
        assertEquals(sessions.value?.userId, expectedUserId)
        listReads.incrementAndGet()
        return entries.filter { it.first == expectedUserId }.map { it.second }
    }

    override suspend fun data(token: String, id: Long): OsmGpxRemoteData {
        assertEquals(sessions.value?.token, token)
        downloaded += id
        if (failData == id) throw IOException("Synthetic download unavailable")
        largeLatitude[id]?.let { latitude ->
            val count = 150_001
            val directions = List(count) { index -> OsmDirectedPoint(latitude, 45.0 + index * .00006) }
            remotePointCounts[id] = directions.size
            return OsmGpxRemoteData(listOf(directions), id.toString(16).padStart(64, '0'))
        }
        val trace = checkNotNull(traces[id])
        return OsmGpxRemoteData(trace.segments.map { segment ->
            segment.map { point -> OsmDirectedPoint(point.latitude, point.longitude) }
        }, trace.sha256)
    }

    fun largeRemote(id: Long, latitude: Double) {
        largeLatitude[id] = latitude
        entries += requireNotNull(sessions.value).userId to
            OsmGpxHistoryEntry(id, "synthetic-large-$id.gpx", 1_740_000_000_000L + id, false)
    }

    fun remote(id: Long, name: String, trace: PreparedOsmGpxTrace,
        visibility: OsmGpxVisibility = OsmGpxVisibility.TRACKABLE) {
        traces[id] = trace
        entries += requireNotNull(sessions.value).userId to OsmGpxHistoryEntry(id, name, 1_740_000_000_000L + id, false, visibility = visibility.wireValue)
    }

    override suspend fun changeToTrackable(token: String, expectedUserId: Long, id: Long,
        canDispatch: () -> Boolean): Boolean {
        assertEquals(sessions.value?.token, token)
        assertEquals(sessions.value?.userId, expectedUserId)
        assertTrue(canDispatch())
        assertEquals(OsmGpxVisibility.IDENTIFIABLE.wireValue, visibility(id))
        changed += id
        if (id == failUpdate) return false
        entries.replaceAll { (owner, trace) ->
            owner to if (owner == expectedUserId && trace.id == id) trace.copy(visibility = OsmGpxVisibility.TRACKABLE.wireValue) else trace
        }
        return true
    }

    fun visibility(id: Long) = entries.single { (owner, trace) ->
        owner == sessions.value?.userId && trace.id == id
    }.second.visibility

    fun switchAccount(next: CommunitySession) {
        sessions.value = next
        auth.value = auth(next)
    }

    private fun auth(session: CommunitySession) =
        OsmAuthUiState(user = OsmUser(session.userId, session.displayName), canWriteGpx = true)
}
