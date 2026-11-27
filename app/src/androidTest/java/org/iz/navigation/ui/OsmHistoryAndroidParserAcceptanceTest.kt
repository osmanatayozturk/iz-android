package org.iz.navigation.ui

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.iz.navigation.MainActivity
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.OsmGpxPreparation
import org.iz.navigation.integration.prepareOsmGpxTrace
import org.iz.navigation.integration.osm.OSM_GPX_READ_SCOPE
import org.iz.navigation.integration.osm.OsmAuthUiState
import org.iz.navigation.integration.osm.OsmGpxController
import org.iz.navigation.integration.osm.OsmGpxFileReceipts
import org.iz.navigation.integration.osm.OsmGpxGateway
import org.iz.navigation.integration.osm.OsmGpxHistoryClient
import org.iz.navigation.integration.osm.OsmGpxHistoryGateway
import org.iz.navigation.integration.osm.OsmGpxReceipt
import org.iz.navigation.integration.osm.OsmGpxReceiptKey
import org.iz.navigation.integration.osm.OsmGpxReceiptStatus
import org.iz.navigation.integration.osm.OsmGpxRemoteData
import org.iz.navigation.integration.osm.OsmUser
import org.iz.navigation.osmcommunity.CommunitySession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmHistoryAndroidParserAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun readsRealisticGpxHistoryThroughTheNativeParserAndCachesTheVisibleTrace() {
        val context = requireDirectionalQa()
        val owner = 284_000_000L + SystemClock.elapsedRealtime()
        val history = HistoryFixture(owner)
        val session = CommunitySession(history.token, owner, "Synthetic OSM history", setOf(OSM_GPX_READ_SCOPE), 1L)
        val auth = MutableStateFlow(OsmAuthUiState(user = OsmUser(owner, session.displayName)))
        val sessions = MutableStateFlow<CommunitySession?>(session)
        val storage = OsmGpxFileReceipts(context.noBackupFilesDir)
        val legacyKey = OsmGpxReceiptKey.of(history.izTrace)
        val installation = OsmGpxController.installForTesting(
            context = context,
            authState = auth,
            sessions = sessions,
            gateway = OsmGpxGateway { _, _, _, _ -> error("History acceptance must not POST") },
            requestPermission = { error("Fixture already grants its synthetic read scope") },
            historyGateway = history.gateway,
        )
        try {
            storage.write(OsmGpxReceipt(owner, legacyKey, OsmGpxReceiptStatus.RECEIVED, IZ_TRACE_ID, "qa-legacy", 1_000L))
            val receiptFile = File(context.noBackupFilesDir, "osm-gpx-receipts/$owner").listFiles().orEmpty().single()
            val legacy = JSONObject(receiptFile.readText())
            legacy.remove("journeyId")
            legacy.remove("directPost")
            receiptFile.writeText(legacy.toString())
            val stored = storage.inventory(owner).single()
            assertEquals(IZ_TRACE_ID, stored.traceId)
            assertNull(stored.journeyId)
            assertTrue(!stored.verified)
            assertNull(storage.coverageCache.inspect(owner, IZ_TRACE_ID))
            compose.onNodeWithTag("home-menu").performClick()
            compose.onNodeWithTag("menu-osm").performScrollTo().performClick()
            compose.onNodeWithTag("osm-contribution-hub").assertIsDisplayed()
            val firstFetch = listOf(LIST_PATH, tracePath(IZ_TRACE_ID))
            val hubList = compose.onNodeWithTag("osm-contribution-list")
            val hubRefresh = hasTestTag("osm-contribution-refresh")
            hubList.performScrollToNode(hubRefresh)
            compose.waitUntil(15_000L) {
                history.requests.size >= firstFetch.size &&
                    compose.onAllNodes(hubRefresh and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            hubList.performScrollToNode(hasTestTag("osm-contribution-gps"))
            compose.onNodeWithTag("osm-contribution-gps").performClick()
            compose.onNodeWithTag("osm-gps-screen").assertIsDisplayed()

            val refresh = hasTestTag("osm-gps-refresh") and isEnabled()
            val coverageIssue = "osm-gps-coverage-issue"
            val readyTag = "osm-gps-list"
            compose.waitUntil(15_000L) {
                history.requests.size >= firstFetch.size && present(coverageIssue)
            }
            assertEquals(firstFetch, history.requests.toList())
            compose.onNodeWithTag(coverageIssue).assertIsDisplayed().assertTextContains("HTTP 503", substring = true)
            compose.onNodeWithTag(readyTag).assertDoesNotExist()
            assertNull(storage.coverageCache.inspect(owner, IZ_TRACE_ID))
            assertVisibleHistoryEntries()
            compose.onNodeWithTag("osm-gps-page-available").performClick()
            compose.onNode(refresh).performClick()

            val issueTags = listOf(coverageIssue, "osm-gps-history-issue")
            compose.waitUntil(15_000L) {
                history.requests.size >= 4 && compose.onAllNodes(refresh).fetchSemanticsNodes().isNotEmpty() &&
                    (present(readyTag) || issueTags.any(::present))
            }
            val issue = issueTags.firstNotNullOfOrNull { tag ->
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            }
            if (issue == null) compose.onNodeWithTag(readyTag).assertIsDisplayed()
            assertEquals(firstFetch + firstFetch, history.requests.take(4))
            assertVisibleHistoryEntries()
            val iz = history.parsed[IZ_TRACE_ID]
            assertNotNull(
                "Both history rows remained visible. The Iz-only GPX failed first: $issue | ${history.parseFailure.get()}",
                iz,
            )
            val legacyParsed = checkNotNull(iz)
            assertEquals(listOf(history.izTrace.pointCount), legacyParsed.segments.map { it.size })
            assertEquals(history.izTrace.sha256, legacyParsed.sha256)
            assertEquals(history.izTrace.sha256, storage.coverageCache.inspect(owner, IZ_TRACE_ID)?.sha256)
            assertNull(
                "Both history rows remained visible; plain Iz GPX cache SHA-256=${legacyParsed.sha256}. " +
                    "Android history showed: $issue | data: ${history.parseFailure.get()}",
                issue,
            )

            val original = history.parsed.getValue(TRACE_ID)
            assertEquals(listOf(3, 2), original.segments.map { it.size })
            val digest = MessageDigest.getInstance("SHA-256").digest(SYNTHETIC_GPX.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(digest, original.sha256)
            val cached = storage.coverageCache.inspect(owner, TRACE_ID)
            assertNotNull(cached)
            assertEquals(digest, cached?.sha256)
            assertEquals(firstFetch + firstFetch + tracePath(TRACE_ID), history.requests.toList())
            assertEquals(IZ_TRACE_ID, storage.read(owner, legacyKey)?.traceId)
            assertTrue(storage.visibilityJournal.attempts(owner).isEmpty())
        } finally {
            installation.close()
            storage.coverageCache.prune(owner, emptySet())
            storage.remove(owner, legacyKey)
        }
    }

    private fun assertVisibleHistoryEntries() {
        compose.onNodeWithTag("osm-gps-page-history").performClick()
        val list = compose.onNodeWithTag("osm-gps-history-list")
        for (id in listOf(TRACE_ID, IZ_TRACE_ID)) {
            val tag = "osm-gps-history-$id"
            list.performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    private fun present(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private class HistoryFixture(owner: Long) {
        val token = "synthetic-history"
        val requests = CopyOnWriteArrayList<String>()
        val parsed = ConcurrentHashMap<Long, OsmGpxRemoteData>()
        val parseFailure = AtomicReference<String>()
        val izTrace = createIzTrace()
        val gateway: OsmGpxHistoryGateway
        private val firstLegacyDownload = AtomicBoolean(true)

        init {
            val client = OsmGpxHistoryClient("http://127.0.0.1/api/0.6")
            val list = """{"traces":[
                {"id":$TRACE_ID,"uid":$owner,"name":"synthetic-history.gpx","timestamp":"2024-05-02T14:32:00Z",
                 "pending":false,"visibility":"trackable","description":"Synthetic history","tags":["qa","gps"]},
                {"id":$IZ_TRACE_ID,"uid":$owner,"name":"${izTrace.fileName}","timestamp":"2024-05-03T14:32:00Z",
                 "pending":false,"visibility":"trackable","description":"","tags":[]}
            ]}"""
            // Keep a937431's client, response parsing and storage code in the app. This app/test pair has no INTERNET.
            // This terminal interceptor supplies complete responses in-process and cannot reach a transport.
            val http = OsmGpxHistoryClient::class.java.getDeclaredField("http").apply { isAccessible = true }
            val transport = (http.get(client) as OkHttpClient).newBuilder().addInterceptor { chain ->
                val req = chain.request()
                if (req.method != "GET" || req.url.host != "127.0.0.1" ||
                    req.header("Authorization") != "Bearer $token") throw IOException("Unexpected fixture request")
                val path = req.url.encodedPath
                requests += path
                val failedDownload = path == tracePath(IZ_TRACE_ID) && firstLegacyDownload.getAndSet(false)
                val (body, type) = when (path) {
                    LIST_PATH -> list to "application/json; charset=UTF-8"
                    tracePath(TRACE_ID) -> SYNTHETIC_GPX to "application/gpx+xml; charset=UTF-8"
                    tracePath(IZ_TRACE_ID) -> izTrace.gpxXml to "application/gpx+xml; charset=UTF-8"
                    else -> throw IOException("Unexpected fixture path")
                }
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                    .code(if (failedDownload) 503 else 200).message(if (failedDownload) "Unavailable" else "OK")
                    .body(if (failedDownload) "".toResponseBody(null) else body.toResponseBody(type.toMediaType())).build()
            }.build()
            http.set(client, transport)
            gateway = object : OsmGpxHistoryGateway by client {
                override suspend fun data(token: String, id: Long): OsmGpxRemoteData = try {
                    client.data(token, id).also { parsed[id] = it }
                } catch (failure: Exception) {
                    parseFailure.set("${failure.javaClass.simpleName}: ${failure.message}")
                    throw failure
                }
            }
        }
    }

    private companion object {
        const val TRACE_ID = 731L
        const val IZ_TRACE_ID = 732L
        const val LIST_PATH = "/api/0.6/user/gpx_files.json"
        fun tracePath(id: Long) = "/api/0.6/gpx/$id/data.gpx"

        fun createIzTrace() = (prepareOsmGpxTrace(
            Journey(id = "qa-generated-history", startedAt = 1_714_662_720_000L, endedAt = 1_714_663_320_000L),
            List(131) { index ->
                TrackPoint(journeyId = "qa-generated-history", latitude = .24, longitude = -.37 + index * .00027,
                    recordedAt = 1_714_662_720_000L + index * 4_000L, accuracy = 3f, speed = 8f)
            },
        ) as OsmGpxPreparation.Ready).trace

        val SYNTHETIC_GPX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="OpenStreetMap"
              xmlns="http://www.topografix.com/GPX/1/1"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
              <metadata><name>Synthetic history</name><time>2024-05-03T14:32:00Z</time></metadata>
              <wpt lat="0.19500" lon="-0.30100"><name>Synthetic waypoint</name></wpt>
              <trk><name>Two synthetic segments</name>
                <trkseg>
                  <trkpt lat="0.20000" lon="-0.30000"><ele>9.5</ele><time>2024-05-03T14:32:00Z</time></trkpt>
                  <trkpt lat="0.20000" lon="-0.29980"><ele>9.6</ele><time>2024-05-03T14:32:04Z</time></trkpt>
                  <trkpt lat="0.20000" lon="-0.29960"><ele>9.4</ele><time>2024-05-03T14:32:08Z</time></trkpt>
                </trkseg>
                <trkseg/>
                <trkseg>
                  <trkpt lat="0.20100" lon="-0.29920"><time>2024-05-03T14:32:35Z</time></trkpt>
                  <trkpt lat="0.20100" lon="-0.29900"><time>2024-05-03T14:32:39Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
    }
}
