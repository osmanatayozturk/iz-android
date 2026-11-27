package org.iz.navigation.integration.osm

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.OsmDirectedPoint
import org.iz.navigation.integration.OsmGpxPreparation
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.integration.prepareOsmGpxTrace
import org.iz.navigation.osmcommunity.CommunitySession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OsmGpxControllerTest {
    @get:Rule val noBackup = TemporaryFolder()

    private val sessions = MutableStateFlow<CommunitySession?>(session(42))
    private val first = route("first", 0, .02)
    private val second = route("second", 1, .08)
    private val third = route("third", 2, .14)
    private val files get() = OsmGpxFileReceipts(noBackup.root)
    private val sources = ConcurrentHashMap(listOf(first, second, third).associateBy { it.journeyId })
    private val remote = SyntheticHistory()

    @Test fun visibleScreensShareOneInitialReadAndOnlyManualRefreshRetriesAFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var reads = 0
        val service = object : OsmGpxHistoryGateway by remote {
            override suspend fun list(token: String, expectedUserId: Long): List<OsmGpxHistoryEntry> {
                if (++reads == 1) {
                    entered.complete(Unit)
                    finish.await()
                    throw IOException("offline")
                }
                return emptyList()
            }
        }
        val controller = newController(OsmGpxGateway { _, _, _, _ -> error("Unexpected upload") }, service)
        val firstScreen = launch { controller.ensureHistory() }
        entered.await()
        val secondScreen = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.refreshHistory() }
        finish.complete(Unit)
        firstScreen.join()
        secondScreen.join()
        controller.ensureHistory()
        assertTrue(controller.history.value is OsmGpxHistoryState.Failed)
        assertEquals(1, reads)
        controller.refreshHistory()
        controller.ensureHistory()
        assertTrue((controller.history.value as OsmGpxHistoryState.Ready).coverageReady)
        assertEquals(2, reads)
    }

    @Test fun trackableConsentIsSingleUseEvenWhenCalledTwice() = runBlocking {
        val submitted = mutableListOf<OsmGpxVisibility>()
        val controller = controller(OsmGpxGateway { _, _, visibility, dispatch ->
            assertTrue(dispatch())
            submitted += visibility
            OsmGpxUploadResult.Received(11L)
        })
        val consent = requireNotNull(controller.prepare(first))
        assertEquals(OsmGpxVisibility.TRACKABLE, consent.visibility)
        assertEquals(OsmGpxStop.COMPLETE, controller.upload(consent, { true }).stop)
        assertEquals(OsmGpxStop.ALREADY_USED, controller.upload(consent, { true }).stop)
        assertEquals(listOf(OsmGpxVisibility.TRACKABLE), submitted)
        val receipt = requireNotNull(files.read(42, OsmGpxReceiptKey.of(first)))
        assertTrue(receipt.directPost)
        assertEquals(11L, receipt.traceId)
    }

    @Test fun partialUploadAndProcessReopeningNeverRetryUnresolvedUnknown() = runBlocking {
        var calls = 0
        val controller = controller { if (++calls == 1) OsmGpxUploadResult.Received(123L) else OsmGpxUploadResult.Unknown }
        val result = controller.upload(requireNotNull(controller.prepare(first, second, third)), { true })
        assertEquals(OsmGpxStop.UNKNOWN, result.stop)
        assertEquals(1, result.unattemptedCount)
        assertEquals(listOf(OsmGpxReceiptStatus.RECEIVED, OsmGpxReceiptStatus.UNKNOWN), result.receipts.map { it.status })
        val unknown = result.receipts.last()
        assertEquals(unknown, files.read(42, OsmGpxReceiptKey.of(second)))
        val reopened = controller { error("An unresolved dispatch must not be repeated") }
        assertNull(reopened.prepare(second, third))
        assertEquals(2, calls)
    }

    @Test fun queueRejectsConcurrentSubmissionAndRateLimitStopsAfterExactPreviewBytes() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val posts = mutableListOf<PreparedOsmGpxTrace>()
        val controller = controller { trace ->
            posts += trace
            if (posts.size == 1) {
                entered.complete(Unit)
                release.await()
                OsmGpxUploadResult.Received(123L)
            } else OsmGpxUploadResult.Rejected(429, 2_000L)
        }
        val preview = requireNotNull(controller.preparePlan(listOf(third.journeyId, second.journeyId, first.journeyId)))
        assertEquals(listOf(first.journeyId, second.journeyId, third.journeyId), preview.traces.map { it.journeyId })
        val consent = requireNotNull(controller.prepareConfirmation(preview))
        val upload = async { controller.upload(consent, { true }) }
        val result = try {
            withTimeout(5_000L) { entered.await() }
            assertEquals(OsmGpxStop.BUSY, controller.upload(consent, { true }).stop)
            assertEquals(1, posts.size)
            release.complete(Unit)
            upload.await()
        } finally { release.complete(Unit) }
        assertEquals(OsmGpxStop.RATE_LIMITED, result.stop)
        assertEquals(429, result.httpStatus)
        assertEquals(2_000L, result.retryAfterMillis)
        assertEquals(1, result.unattemptedCount)
        assertEquals(preview.traces.take(2).map { it.gpxBytes }, posts.map { it.gpxBytes })
        assertEquals(123L, files.read(42, OsmGpxReceiptKey.of(first))?.traceId)
        assertNull(files.read(42, OsmGpxReceiptKey.of(second)))
    }

    @Test fun onlyVerifiedDirectPostReceiptsCanSkipRemoteDownload() = runBlocking {
        val key = OsmGpxReceiptKey.of(first)
        remote.add(42, 30L, first.fileName, first)
        val oldReceipt = OsmGpxReceipt(42, key, OsmGpxReceiptStatus.RECEIVED, 30L, "legacy", 1_000L)
        files.write(oldReceipt)
        val legacyFile = File(noBackup.root, "osm-gpx-receipts/42").listFiles().orEmpty().single { it.extension == "json" }
        val oldJson = JSONObject(legacyFile.readText()).apply {
            remove("directPost")
            remove("journeyId")
        }
        legacyFile.writeText(oldJson.toString())
        val old = controller { error("An already covered route must not dispatch") }
        assertNull(old.prepare(first))
        assertTrue(30L in remote.downloaded)
        remote.downloaded.clear()

        // A new 2xx receipt has authenticated object identity plus an exact local GPX digest.
        remote.remove(30L)
        remote.add(42, 31L, first.fileName, first)
        files.write(oldReceipt.copy(traceId = 31L, directPost = true))
        val direct = controller { error("An already covered route must not dispatch") }
        assertEquals(listOf(OsmGpxReceiptKey.of(second)), direct.prepare(first, second)?.keys)
        assertTrue("Exact verified POST should reuse its local geometry", remote.downloaded.isEmpty())
    }

    @Test fun receiptsAreScopedToTheSendingAccount() = runBlocking {
        val sender = controller { OsmGpxUploadResult.Received(19L) }
        sender.upload(requireNotNull(sender.prepare(first)), { true })
        val sent = requireNotNull(files.read(42, OsmGpxReceiptKey.of(first)))
        assertEquals(19L, sent.traceId)
        assertEquals("https://www.openstreetmap.org/user/A%20B/traces/19", sent.traceUrl(OsmUser(42, "A B")))
        sessions.value = session(43)
        val newAccount = controller { error("Preparing must not dispatch") }
        assertNull(sent.traceUrl(OsmUser(43, "A B")))
        assertNull(files.read(43, OsmGpxReceiptKey.of(first)))
        assertNotNull(newAccount.prepare(first))
    }

    @Test fun aNewRemoteUploadInvalidatesOldConsentBeforeAnyDispatch() = runBlocking {
        val controller = controller { error("Stale preview must never be posted") }
        val consent = requireNotNull(controller.prepare(first))
        remote.add(42, 41L, "elsewhere.gpx", first)
        val result = controller.upload(consent, { true })
        assertEquals(OsmGpxStop.CHANGED, result.stop)
        assertEquals(1, result.unattemptedCount)
        assertNull(files.read(42, OsmGpxReceiptKey.of(first)))
        assertNull(controller.prepare(first))
    }

    @Test fun foregroundLossBeforeAndAfterFirstPostNeverDispatchesTheNextTrace() = runBlocking {
        var foreground = true
        var count = 0
        val controller = controller { count++; foreground = false; OsmGpxUploadResult.Received(12L) }
        val earlyConsent = requireNotNull(controller.prepare(first))
        val early = controller.upload(earlyConsent, { foreground }) { _, _ -> foreground = false }
        assertEquals(OsmGpxStop.BACKGROUND, early.stop)
        assertEquals(1, early.unattemptedCount)
        assertEquals(0, count)
        assertNull(files.read(42, OsmGpxReceiptKey.of(first)))
        foreground = true
        val later = controller.upload(requireNotNull(controller.prepare(first, second)), { foreground })
        assertEquals(OsmGpxStop.BACKGROUND, later.stop)
        assertEquals(1, later.unattemptedCount)
        assertEquals(1, count)
        assertNotNull(files.read(42, OsmGpxReceiptKey.of(first)))
        assertNull(files.read(42, OsmGpxReceiptKey.of(second)))
    }

    @Test fun accountChangeStopsQueueButKeepsFirstReceiptWithItsSender() = runBlocking {
        val controller = controller {
            sessions.value = session(43)
            OsmGpxUploadResult.Received(12L)
        }
        val result = controller.upload(requireNotNull(controller.prepare(first, second)), { true })
        assertEquals(OsmGpxStop.ACCOUNT_CHANGED, result.stop)
        assertEquals(1, result.unattemptedCount)
        assertEquals(12L, files.read(42, OsmGpxReceiptKey.of(first))?.traceId)
        assertNull(files.read(43, OsmGpxReceiptKey.of(first)))
        assertNull(files.read(42, OsmGpxReceiptKey.of(second)))
    }

    @Test fun cancellationLeavesDurableUnknownBeforeAnyResponse() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val controller = controller {
            entered.complete(Unit)
            awaitCancellation()
        }
        val consent = requireNotNull(controller.prepare(first))
        val pending = launch { controller.upload(consent, { true }) }
        withTimeout(5_000L) { entered.await() }
        pending.cancelAndJoin()
        val reopened = controller { error("Reopened controller must not resend") }
        assertEquals(OsmGpxReceiptStatus.UNKNOWN, files.read(42, OsmGpxReceiptKey.of(first))?.status)
        assertNull(reopened.prepare(first))
    }

    @Test fun freshReadAndWriteScopesAndTheOriginalSessionAreBothRequired() = runBlocking {
        sessions.value = session(42).copyForTest(scopes = setOf(OSM_GPX_READ_SCOPE))
        val controller = controller { error("Outdated consent must not dispatch") }
        assertNull(controller.prepare(first))
        sessions.value = session(42)
        controller.refreshHistory()
        val consent = requireNotNull(controller.prepare(first))
        sessions.value = session(42, generation = 3)
        assertEquals(OsmGpxStop.ACCOUNT_CHANGED, controller.upload(consent, { true }).stop)
        assertNull(files.read(42, OsmGpxReceiptKey.of(first)))
    }

    @Test fun missingGeometryAndStaleOtherAccountResponsesFailClosed() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val history = object : OsmGpxHistoryGateway by remote {
            override suspend fun list(token: String, expectedUserId: Long): List<OsmGpxHistoryEntry> {
                if (expectedUserId == 42L) {
                    entered.complete(Unit)
                    finish.await()
                }
                return remote.list(token, expectedUserId)
            }
        }
        remote.add(43, 29, "remote-other-app.gpx", second)
        remote.failedId = 29
        val controller = newController(OsmGpxGateway { _, _, _, _ -> error("Coverage is incomplete") }, history)
        val oldRequest = launch { controller.refreshHistory() }
        withTimeout(5_000L) { entered.await() }
        sessions.value = session(43)
        controller.refreshHistory()
        assertNull(controller.prepare(first))
        remote.failedId = null
        controller.refreshHistory()
        finish.complete(Unit)
        oldRequest.join()
        val ready = controller.history.value as OsmGpxHistoryState.Ready
        assertEquals(43L, ready.user.id)
        assertEquals(listOf(OsmGpxReceiptKey.of(first)), controller.prepare(first, second)?.keys)
    }

    @Test fun onlyUniqueAuthenticatedGpxBytesReconcileUnknownForTheCurrentAccount() = runBlocking {
        val key = OsmGpxReceiptKey.of(first)
        val before = OsmGpxReceipt(42, key, OsmGpxReceiptStatus.UNKNOWN, null, "private-attempt", 10)
        files.write(before)
        files.write(before.copy(accountId = 43))
        remote.add(42, 89L, first.fileName, second)
        val controller = controller { error("Unknown must never re-dispatch") }
        assertNull(controller.prepare(first))
        assertEquals(before, files.read(42, key))

        remote.add(42, 91L, first.fileName, first)
        remote.add(42, 92L, "second-exact.gpx", first)
        controller.refreshHistory()
        assertNull(controller.prepare(first))
        assertEquals(before, files.read(42, key))
        remote.remove(92L)
        controller.refreshHistory()
        assertNull(controller.prepare(first))
        val resolved = controller.receiptsForKeys(listOf(key)).getValue(key)
        assertEquals(OsmGpxReceiptStatus.RECEIVED, resolved.status)
        assertEquals(91L, resolved.traceId)
        assertFalse(resolved.directPost)
        assertEquals(before.copy(accountId = 43), files.read(43, key))
    }

    @Test fun perFileRejectionContinuesAndTheAcceptedReceiptIsVisibleBeforeTheNextPost() = runBlocking {
        val posted = mutableListOf<OsmGpxReceiptKey>()
        val keys = listOf(first, second, third).map(OsmGpxReceiptKey::of)
        val controller = controller { trace ->
            val key = OsmGpxReceiptKey.of(trace)
            posted += key
            when (key) {
                keys[0] -> OsmGpxUploadResult.Rejected(422)
                keys[1] -> OsmGpxUploadResult.Received(90)
                else -> {
                    assertEquals(90L, files.read(42, keys[1])?.traceId)
                    OsmGpxUploadResult.Received(91)
                }
            }
        }
        val result = controller.upload(requireNotNull(controller.prepare(first, second, third)), { true })
        assertEquals(keys, posted)
        assertEquals(OsmGpxStop.COMPLETE, result.stop)
        assertEquals(mapOf(keys[0] to 422), result.rejectedKeys)
        assertEquals(0, result.unattemptedCount)
        controller.refreshHistory()
        assertEquals(listOf(keys[0]), controller.prepare(first, second, third)?.keys)
    }

    @Test fun changedLocalPayloadStopsBeforeReservationOrPost() = runBlocking {
        val controller = controller { error("Changed payload needs new preview") }
        val consent = requireNotNull(controller.prepare(first, second))
        sources[first.journeyId] = route(first.journeyId, 0, .035)
        val result = controller.upload(consent, { true })
        assertEquals(OsmGpxStop.CHANGED, result.stop)
        assertEquals(2, result.unattemptedCount)
        assertNull(files.read(42, OsmGpxReceiptKey.of(first)))
    }

    @Test fun failedReceiptWriteLeavesUnknownAndStopsBeforeAnotherPost() = runBlocking {
        val actual = files
        val store = object : OsmGpxReceiptStore by actual {
            override fun write(receipt: OsmGpxReceipt) {
                if (receipt.status == OsmGpxReceiptStatus.RECEIVED) throw IOException("Synthetic local failure")
                actual.write(receipt)
            }
        }
        val posted = mutableListOf<OsmGpxReceiptKey>()
        val controller = newController(OsmGpxGateway { _, trace, _, _ ->
            posted += OsmGpxReceiptKey.of(trace)
            OsmGpxUploadResult.Received(90)
        }, store = store).also { it.refreshHistory() }
        val result = controller.upload(requireNotNull(controller.prepare(first, second)), { true })
        assertEquals(OsmGpxStop.STORAGE_ERROR, result.stop)
        assertEquals(1, result.unattemptedCount)
        assertEquals(listOf(OsmGpxReceiptKey.of(first)), posted)
        assertEquals(OsmGpxReceiptStatus.UNKNOWN, actual.read(42, OsmGpxReceiptKey.of(first))?.status)
        assertNull(actual.read(42, OsmGpxReceiptKey.of(second)))
    }

    @Test fun anOversizedFileOrExcessiveLocalCoverageRefusesTheWholePlan() = runBlocking {
        remote.addGeometry(42L, 701L, count = 300_001, latitude = 30.0, longitude = 40.0)
        val controller = controller { error("An incomplete plan must not dispatch") }
        assertFalse((controller.history.value as OsmGpxHistoryState.Ready).coverageReady)
        assertNull(controller.preparePlan(listOf(first.journeyId, second.journeyId)))

        remote.remove(701L)
        remote.addGeometry(42L, 702L, count = 150_001, latitude = .020001, longitude = -.291)
        remote.addGeometry(42L, 703L, count = 150_001, latitude = .020002, longitude = -.291)
        controller.refreshHistory()
        assertTrue((controller.history.value as OsmGpxHistoryState.Ready).coverageReady)
        assertEquals(listOf(OsmGpxReceiptKey.of(second)), controller.prepare(second)?.keys)
        assertNull("A locally over-dense region must not leave the other selected trace sendable",
            controller.preparePlan(listOf(first.journeyId, second.journeyId)))
    }

    private suspend fun OsmGpxController.prepare(vararg traces: PreparedOsmGpxTrace) =
        preparePlan(traces.map { it.journeyId })?.let { prepareConfirmation(it) }

    private suspend fun controller(upload: suspend (PreparedOsmGpxTrace) -> OsmGpxUploadResult) =
        controller(OsmGpxGateway { _, trace, _, dispatch -> assertTrue(dispatch()); upload(trace) })

    private suspend fun controller(gateway: OsmGpxGateway) = newController(gateway).also { it.refreshHistory() }

    private fun newController(
        gateway: OsmGpxGateway,
        history: OsmGpxHistoryGateway = remote,
        store: OsmGpxReceiptStore = files,
    ) = OsmGpxController(
        currentSession = { sessions.value },
        gateway = OsmGpxGateway { token, trace, visibility, dispatch ->
            val account = requireNotNull(sessions.value).userId
            val response = gateway.upload(token, trace, visibility, dispatch)
            if (response is OsmGpxUploadResult.Received) remote.add(account, response.traceId, trace.fileName, trace)
            response
        },
        historyGateway = history,
        store = store,
        authState = MutableStateFlow(OsmAuthUiState()),
        sessions = sessions,
        permissionRequest = {},
        loadJourney = { id -> sources[id]?.let { it.segments.first().first().recordedAt to it } },
    )

    private fun session(id: Long, generation: Long = 2) = CommunitySession(
        "synthetic-$id-$generation", id, "Fixture user", setOf(OSM_GPX_READ_SCOPE, OSM_GPX_WRITE_SCOPE), generation,
    )

    private fun CommunitySession.copyForTest(scopes: Set<String>) =
        CommunitySession(token, userId, displayName, scopes, generation)

    private fun route(id: String, order: Int, latitude: Double): PreparedOsmGpxTrace {
        val at = 1_740_000_000_000L + order * 900_000L
        val journey = Journey(id = id, startedAt = at, endedAt = at + 600_000L)
        val points = (0..130).map { index ->
            TrackPoint(journeyId = id, latitude = latitude, longitude = -.3 + .00027 * index,
                recordedAt = at + index * 4_000L, accuracy = 3f, speed = 8f)
        }
        return (prepareOsmGpxTrace(journey, points) as OsmGpxPreparation.Ready).trace
    }

    private class SyntheticHistory : OsmGpxHistoryGateway {
        private data class Entry(val account: Long, val metadata: OsmGpxHistoryEntry, val trace: PreparedOsmGpxTrace)

        private val entries = ConcurrentHashMap<Long, Entry>()
        private val large = ConcurrentHashMap<Long, Pair<Long, OsmGpxRemoteData>>()
        val downloaded = java.util.concurrent.CopyOnWriteArrayList<Long>()
        @Volatile var failedId: Long? = null

        fun add(account: Long, id: Long, name: String, trace: PreparedOsmGpxTrace) {
            entries[id] = Entry(account, OsmGpxHistoryEntry(id, name, 1_740_000_000_000L + id, false), trace)
        }

        fun addGeometry(account: Long, id: Long, count: Int, latitude: Double, longitude: Double) {
            val points = List(count) { index -> OsmDirectedPoint(latitude, longitude + index * .00000005) }
            large[id] = account to OsmGpxRemoteData(listOf(points), id.toString(16).padStart(64, '0'))
        }

        fun remove(id: Long) {
            entries.remove(id)
            large.remove(id)
        }

        override suspend fun list(token: String, expectedUserId: Long) =
            entries.values.filter { it.account == expectedUserId }.map { it.metadata } +
                large.entries.filter { it.value.first == expectedUserId }.map { (id, _) ->
                    OsmGpxHistoryEntry(id, "geometry-$id.gpx", id, false)
                }

        override suspend fun data(token: String, id: Long): OsmGpxRemoteData {
            downloaded += id
            if (failedId == id) throw IOException("Missing synthetic GPX")
            large[id]?.let { (account, data) ->
                assertTrue(token.startsWith("synthetic-$account-"))
                return data
            }
            val entry = entries.getValue(id)
            assertTrue(token.startsWith("synthetic-${entry.account}-"))
            val trace = entry.trace
            return OsmGpxRemoteData(trace.segments.map { segment ->
                segment.map { point -> OsmDirectedPoint(point.latitude, point.longitude) }
            }, trace.sha256)
        }
    }
}
