package org.iz.navigation.integration.osm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.osmcommunity.CommunitySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OsmGpxControllerTest {
    @get:Rule val noBackup = TemporaryFolder()

    private val sessions = MutableStateFlow<CommunitySession?>(session(42))
    private val first = osmGpxTestTrace("journey-first")
    private val second = osmGpxTestTrace("journey-second", startedAt = 1_200_000)
    private val third = osmGpxTestTrace("journey-third", startedAt = 1_900_000)

    @Test fun changedVisibilityRetiresOldConsentAndCannotBypassAnUnknownOrDuplicate() = runBlocking {
        val calls = mutableListOf<Pair<OsmGpxReceiptKey, OsmGpxVisibility>>()
        val controller = controllerWithGateway(OsmGpxGateway { _, trace, visibility, canDispatch ->
            check(canDispatch())
            calls += OsmGpxReceiptKey.of(trace) to visibility
            if (calls.size == 1) OsmGpxUploadResult.Unknown else OsmGpxUploadResult.Received(42)
        })
        val key = OsmGpxReceiptKey.of(first)
        val initial = checkNotNull(controller.prepareConfirmation(listOf(first, first)))
        assertEquals(OsmGpxVisibility.IDENTIFIABLE, initial.visibility)
        val trackable = checkNotNull(initial.replaceVisibility(OsmGpxVisibility.TRACKABLE))
        assertEquals(initial.user, trackable.user)
        assertEquals(listOf(key), trackable.keys)
        assertEquals(OsmGpxStop.ALREADY_USED, controller.upload(initial, { true }).stop)
        assertTrue(calls.isEmpty())
        val unknown = controller.upload(trackable, { true }).receipts.single()
        assertEquals(OsmGpxReceiptStatus.UNKNOWN, unknown.status)
        assertNull(controller.prepareConfirmationForKeys(listOf(key), visibility = OsmGpxVisibility.IDENTIFIABLE))
        val reviewed = checkNotNull(controller.prepareConfirmation(listOf(first), setOf(unknown)))
        assertEquals(OsmGpxStop.COMPLETE, controller.upload(reviewed, { true }).stop)
        assertNull(controller.prepareConfirmationForKeys(listOf(key), visibility = OsmGpxVisibility.TRACKABLE))
        assertEquals(listOf(key to OsmGpxVisibility.TRACKABLE, key to OsmGpxVisibility.IDENTIFIABLE), calls)
    }

    @Test fun recordsPartialSuccessAndRequiresExactUnknownReviewBeforeFreshConsent() = runBlocking {
        val calls = mutableListOf<PreparedOsmGpxTrace>()
        val attempts = mutableListOf<Pair<OsmGpxReceiptKey, Int>>()
        val controller = controller { trace ->
            calls += trace
            if (calls.size == 2) OsmGpxUploadResult.Unknown else OsmGpxUploadResult.Received(123)
        }
        val traces = listOf(first, second, third)
        val confirmation = checkNotNull(controller.prepareConfirmation(traces))
        assertEquals(OsmGpxReceiptKey.of(first), confirmation.keys.first())
        val result = controller.upload(confirmation, { true }) { key, index -> attempts += key to index }
        assertEquals(OsmGpxStop.UNKNOWN, result.stop)
        assertEquals(1, result.unattemptedCount)
        assertEquals(listOf(OsmGpxReceiptStatus.RECEIVED, OsmGpxReceiptStatus.UNKNOWN), result.receipts.map { it.status })
        assertEquals(listOf(first, second), calls)
        assertEquals(listOf(OsmGpxReceiptKey.of(first) to 1, OsmGpxReceiptKey.of(second) to 2), attempts)
        assertEquals(OsmGpxStop.ALREADY_USED, controller.upload(confirmation, { true }).stop)
        assertNull(controller.prepareConfirmation(listOf(first, second)))

        val unknown = result.receipts.last()
        val retry = checkNotNull(controller.prepareConfirmation(listOf(first, second), setOf(unknown)))
        assertEquals(listOf(OsmGpxReceiptKey.of(second)), retry.keys)
        assertEquals(OsmGpxStop.COMPLETE, controller.upload(retry, { true }).stop)
        assertNull(controller.prepareConfirmation(listOf(first, second), setOf(unknown)))
    }

    @Test fun wireQueueRejectsConcurrentSubmitAndStopsAfterRateLimit() = runBlocking {
        MockWebServer().use { server ->
            val firstPost = AtomicBoolean(true)
            val releaseFirst = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (firstPost.getAndSet(false)) {
                        return if (releaseFirst.await(10, TimeUnit.SECONDS)) MockResponse().setBody("123")
                        else MockResponse().setResponseCode(500)
                    }
                    return MockResponse().setResponseCode(429).addHeader("Retry-After", "2")
                }
            }
            val client = OsmGpxClient(server.url("/api/0.6").toString())
            val controller = controllerWithGateway(client)
            val rateLimited = osmGpxTestTrace("wire-second", startedAt = 1_400_000)
            val unsent = osmGpxTestTrace("wire-third", startedAt = 1_800_000)
            val traces = listOf(first, rateLimited, unsent)

            withTimeout(15_000) {
                val confirmation = checkNotNull(controller.prepareConfirmation(traces))
                val upload = async { controller.upload(confirmation, { true }) }
                val result = try {
                    val received = withContext(Dispatchers.IO) {
                        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    }
                    assertTracePost(received, first)
                    assertEquals(OsmGpxStop.BUSY, controller.upload(confirmation, { true }).stop)
                    assertEquals(1, server.requestCount)
                    releaseFirst.countDown()
                    upload.await()
                } finally {
                    releaseFirst.countDown()
                }
                assertEquals(OsmGpxStop.RATE_LIMITED, result.stop)
                assertEquals(429, result.httpStatus)
                assertEquals(2_000L, result.retryAfterMillis)
                assertEquals(1, result.unattemptedCount)
                assertTracePost(checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)), rateLimited)
                assertEquals(2, server.requestCount)

                val receipt = result.receipts.single()
                assertEquals(OsmGpxReceiptStatus.RECEIVED, receipt.status)
                assertEquals(123L, receipt.traceId)
                val reopened = controllerWithGateway(client)
                assertEquals(mapOf(OsmGpxReceiptKey.of(first) to receipt), reopened.receipts(traces))
                assertNull(reopened.prepareConfirmation(listOf(first)))
                assertEquals(listOf(rateLimited, unsent).map { OsmGpxReceiptKey.of(it) },
                    checkNotNull(reopened.prepareConfirmation(traces)).keys)
                assertEquals(2, server.requestCount)
            }
        }
    }

    private fun assertTracePost(request: RecordedRequest, trace: PreparedOsmGpxTrace) {
        assertEquals("POST", request.method)
        assertEquals("/api/0.6/gpx", request.path)
        assertTrue(request.body.readUtf8().contains("filename=\"" + trace.fileName + "\""))
    }

    @Test fun receiptReadbackIsAccountScopedAcrossControllerInstances() = runBlocking {
        val controller = controller { OsmGpxUploadResult.Received(19) }
        controller.upload(checkNotNull(controller.prepareConfirmation(listOf(first))), { true })
        val reopened = controller { error("Readback must not contact OSM") }
        val receipt = checkNotNull(reopened.receipts(listOf(first))[OsmGpxReceiptKey.of(first)])
        assertEquals(19L, receipt.traceId)
        assertEquals("https://www.openstreetmap.org/user/A%20B/traces/19", receipt.traceUrl(OsmUser(42, "A B")))
        sessions.value = session(43)
        assertTrue(reopened.receipts(listOf(first)).isEmpty())
        assertNull(receipt.traceUrl(OsmUser(43, "A B")))
        reopened.refreshHistory()
        assertEquals(1, reopened.prepareConfirmation(listOf(first))?.traceCount)
    }

    @Test fun rejectsStaleConsentBeforeDispatching() = runBlocking {
        var calls = 0
        val controller = controller { calls++; OsmGpxUploadResult.Received(12) }
        val one = checkNotNull(controller.prepareConfirmation(listOf(first)))
        val stale = checkNotNull(controller.prepareConfirmation(listOf(first)))
        assertEquals(OsmGpxStop.COMPLETE, controller.upload(one, { true }).stop)
        assertEquals(OsmGpxStop.STALE_CONFIRMATION, controller.upload(stale, { true }).stop)
        assertEquals(1, calls)
    }

    @Test fun backgroundAfterFirstReceiptStopsRemainingQueue() = runBlocking {
        var foreground = true
        val controller = controller {
            foreground = false
            OsmGpxUploadResult.Received(12)
        }
        val result = controller.upload(checkNotNull(controller.prepareConfirmation(listOf(first, second))), { foreground })
        assertEquals(OsmGpxStop.BACKGROUND, result.stop)
        assertEquals(1, result.unattemptedCount)
        assertEquals(setOf(OsmGpxReceiptKey.of(first)), controller.receipts(listOf(first, second)).keys)
    }

    @Test fun foregroundLossBeforeDispatchLeavesNoAttemptReceipt() = runBlocking {
        var foreground = true
        var calls = 0
        val controller = controller { calls++; OsmGpxUploadResult.Received(12) }
        val confirmation = checkNotNull(controller.prepareConfirmation(listOf(first)))
        val result = controller.upload(confirmation, { foreground }) { _, _ -> foreground = false }
        assertEquals(OsmGpxStop.BACKGROUND, result.stop)
        assertEquals(1, result.unattemptedCount)
        assertEquals(0, calls)
        assertTrue(controller.receipts(listOf(first)).isEmpty())
    }

    @Test fun accountChangeStopsRemainingQueueAndKeepsReceiptWithSendingAccount() = runBlocking {
        val controller = controller {
            sessions.value = session(43)
            OsmGpxUploadResult.Received(12)
        }
        val result = controller.upload(checkNotNull(controller.prepareConfirmation(listOf(first, second))), { true })
        assertEquals(OsmGpxStop.ACCOUNT_CHANGED, result.stop)
        assertEquals(1, result.unattemptedCount)
        assertTrue(controller.receipts(listOf(first, second)).isEmpty())
        sessions.value = session(42)
        assertEquals(setOf(OsmGpxReceiptKey.of(first)), controller.receipts(listOf(first, second)).keys)
    }

    @Test fun rateLimitPreservesPriorUnknownAndDoesNotContinue() = runBlocking {
        var calls = 0
        val controller = controller {
            if (++calls == 1) OsmGpxUploadResult.Unknown else OsmGpxUploadResult.Rejected(429, 2_000)
        }
        val prior = controller.upload(checkNotNull(controller.prepareConfirmation(listOf(first))), { true }).receipts.single()
        val confirmation = checkNotNull(controller.prepareConfirmation(listOf(first, second), setOf(prior)))
        val result = controller.upload(confirmation, { true })
        assertEquals(OsmGpxStop.RATE_LIMITED, result.stop)
        assertEquals(1, result.unattemptedCount)
        assertEquals(2_000L, result.retryAfterMillis)
        assertEquals(listOf(prior), controller.receipts(listOf(first, second)).values.toList())
        assertEquals(2, calls)
    }

    @Test fun cancellationLeavesAnUnknownThatRequiresReviewAfterReopening() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val controller = controller {
            entered.complete(Unit)
            awaitCancellation()
        }
        val confirmation = checkNotNull(controller.prepareConfirmationForKeys(
            listOf(OsmGpxReceiptKey.of(first)), visibility = OsmGpxVisibility.TRACKABLE,
        ))
        val upload = launch { controller.upload(confirmation, { true }) }
        entered.await()
        upload.cancelAndJoin()
        val reopened = controller { error("Reopening must not resend") }
        assertEquals(OsmGpxReceiptStatus.UNKNOWN, reopened.receipts(listOf(first)).values.single().status)
        assertNull(reopened.prepareConfirmation(listOf(first)))
    }

    @Test fun consentRequiresGpxScopeAndFreshSessionAfterPermissionFlow() = runBlocking {
        sessions.value = CommunitySession("fixture", 42, "Fixture user", setOf("read_prefs"), 1)
        val controller = controller { error("An outdated consent cannot dispatch") }
        assertNull(controller.prepareConfirmation(listOf(first)))
        sessions.value = session(42)
        controller.refreshHistory()
        val firstConsent = checkNotNull(controller.prepareConfirmation(listOf(first)))
        assertSame(sessions.value, controller.sessions.value)
        sessions.value = session(42, generation = 3)
        assertEquals(3L, controller.sessions.value?.generation)
        assertEquals(OsmGpxStop.ACCOUNT_CHANGED, controller.upload(firstConsent, { true }).stop)
        assertFalse(controller.receipts(listOf(first)).containsKey(OsmGpxReceiptKey.of(first)))
    }

    @Test fun failedHistoryAndLateOtherAccountHistoryCannotUnlockConsent() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val firstKey = OsmGpxReceiptKey.of(first)
        val secondKey = OsmGpxReceiptKey.of(second)
        val fresh = OsmGpxHistoryEntry(19, second.fileName, 200, true)
        var otherAccountReads = 0
        val controller = newController(
            OsmGpxGateway { _, _, _, _ -> error("Consent has not been given") },
            OsmGpxHistoryGateway { token, accountId ->
                if (token == "fixture") {
                    assertEquals(42L, accountId)
                    entered.complete(Unit)
                    finish.await()
                    listOf(OsmGpxHistoryEntry(12, first.fileName, 100, false))
                } else {
                    assertEquals(43L, accountId)
                    if (++otherAccountReads == 1) throw java.io.IOException("fixture")
                    listOf(fresh)
                }
            },
        )
        assertNull(controller.prepareConfirmationForKeys(listOf(firstKey)))
        val oldRequest = launch { controller.refreshHistory() }
        entered.await()
        sessions.value = CommunitySession("fixture-new", 43, "Another user", setOf("read_gpx", "write_gpx"), 3)
        controller.refreshHistory()
        assertEquals(OsmGpxHistoryState.Failed, controller.history.value)
        assertNull(controller.prepareConfirmationForKeys(listOf(firstKey)))
        controller.refreshHistory()
        finish.complete(Unit)
        oldRequest.join()
        assertEquals(OsmGpxHistoryState.Ready(OsmUser(43, "Another user"), listOf(fresh)), controller.history.value)
        assertEquals(listOf(firstKey), controller.prepareConfirmationForKeys(listOf(firstKey, secondKey))?.keys)
    }

    @Test fun exactHistoryNameResolvesOnlyTheCurrentAccountsUnknownReceipt() = runBlocking {
        val key = OsmGpxReceiptKey.of(first)
        val store = OsmGpxFileReceipts(noBackup.root)
        val oldAttempt = OsmGpxReceipt(42, key, OsmGpxReceiptStatus.UNKNOWN, null, "first-attempt", 10)
        store.write(oldAttempt)
        store.write(oldAttempt.copy(accountId = 43))
        val controller = newController(
            OsmGpxGateway { _, _, _, _ -> error("Already received") },
            OsmGpxHistoryGateway { _, _ -> listOf(
                OsmGpxHistoryEntry(90, first.fileName.uppercase(), 200, false),
                OsmGpxHistoryEntry(91, first.fileName, 100, true),
            ) },
            store,
        )
        controller.refreshHistory()
        val resolved = controller.receiptsForKeys(listOf(key)).getValue(key)
        assertEquals(oldAttempt.copy(status = OsmGpxReceiptStatus.RECEIVED, traceId = 91), resolved)
        assertEquals(resolved, store.read(42, key))
        assertEquals(oldAttempt.copy(accountId = 43), store.read(43, key))
        assertNull(controller.prepareConfirmationForKeys(listOf(key), setOf(oldAttempt)))
    }

    @Test fun perFileRejectionContinuesAndReceiptAppearsBeforeTheNextPost() = runBlocking {
        val posted = mutableListOf<OsmGpxReceiptKey>()
        val store = OsmGpxFileReceipts(noBackup.root)
        val keys = listOf(first, second, third).map { OsmGpxReceiptKey.of(it) }
        lateinit var controller: OsmGpxController
        controller = newController(OsmGpxGateway { _, trace, _, _ ->
            val key = OsmGpxReceiptKey.of(trace)
            posted += key
            when (key) {
                keys[0] -> OsmGpxUploadResult.Rejected(422)
                keys[1] -> OsmGpxUploadResult.Received(90)
                else -> {
                    assertEquals(90L, store.read(42, keys[1])?.traceId)
                    assertEquals(listOf(90L), (controller.history.value as OsmGpxHistoryState.Ready).entries.map { it.id })
                    OsmGpxUploadResult.Received(91)
                }
            }
        }, store = store)
        controller.refreshHistory()
        val result = controller.upload(checkNotNull(controller.prepareConfirmationForKeys(keys)), { true })
        assertEquals(keys, posted)
        assertEquals(OsmGpxStop.COMPLETE, result.stop)
        assertEquals(mapOf(keys[0] to 422), result.rejectedKeys)
        assertEquals(0, result.unattemptedCount)
        assertEquals(listOf(keys[0]), controller.prepareConfirmationForKeys(keys)?.keys)
    }

    @Test fun changedPayloadStopsBeforeReservationOrPost() = runBlocking {
        val controller = controller { error("A different payload must not be posted") }
        val confirmation = checkNotNull(controller.prepareConfirmation(listOf(first, second)))
        val result = controller.upload(confirmation, { true }, prepare = { second })
        assertEquals(OsmGpxStop.STALE_CONFIRMATION, result.stop)
        assertEquals(2, result.unattemptedCount)
        assertTrue(controller.receipts(listOf(first, second)).isEmpty())
    }

    @Test fun failedReceiptWriteDoesNotPublishSuccessOrDispatchAnotherTrace() = runBlocking {
        val files = OsmGpxFileReceipts(noBackup.root)
        val store = object : OsmGpxReceiptStore by files {
            override fun write(receipt: OsmGpxReceipt) {
                if (receipt.status == OsmGpxReceiptStatus.RECEIVED) throw java.io.IOException("fixture")
                files.write(receipt)
            }
        }
        val posted = mutableListOf<OsmGpxReceiptKey>()
        val keys = listOf(first, second).map { OsmGpxReceiptKey.of(it) }
        val controller = newController(OsmGpxGateway { _, trace, _, _ ->
            posted += OsmGpxReceiptKey.of(trace)
            OsmGpxUploadResult.Received(90)
        }, store = store)
        controller.refreshHistory()
        val result = controller.upload(checkNotNull(controller.prepareConfirmationForKeys(keys)), { true })
        assertEquals(OsmGpxStop.STORAGE_ERROR, result.stop)
        assertEquals(1, result.unattemptedCount)
        assertEquals(listOf(keys.first()), posted)
        assertEquals(OsmGpxReceiptStatus.UNKNOWN, result.receipts.single().status)
        assertTrue((controller.history.value as OsmGpxHistoryState.Ready).entries.isEmpty())
        assertEquals(OsmGpxReceiptStatus.UNKNOWN, files.read(42, keys.first())?.status)
    }

    private suspend fun controller(upload: suspend (PreparedOsmGpxTrace) -> OsmGpxUploadResult) =
        controllerWithGateway(OsmGpxGateway { _, trace, _, canDispatch ->
            check(canDispatch())
            upload(trace)
        })

    private suspend fun controllerWithGateway(gateway: OsmGpxGateway) =
        newController(gateway).also { it.refreshHistory() }

    private fun newController(
        gateway: OsmGpxGateway,
        history: OsmGpxHistoryGateway = OsmGpxHistoryGateway { _, _ -> emptyList() },
        store: OsmGpxReceiptStore = OsmGpxFileReceipts(noBackup.root),
    ) = OsmGpxController(
        currentSession = { sessions.value },
        gateway = gateway,
        store = store,
        authState = MutableStateFlow(OsmAuthUiState()),
        sessions = sessions,
        permissionRequest = {},
        historyGateway = history,
    )

    private val tracesByKey by lazy {
        listOf(first, second, third,
            osmGpxTestTrace("wire-second", startedAt = 1_400_000),
            osmGpxTestTrace("wire-third", startedAt = 1_800_000)).associateBy { OsmGpxReceiptKey.of(it) }
    }

    private suspend fun OsmGpxController.prepareConfirmation(
        traces: List<PreparedOsmGpxTrace>,
        reviewedUnknown: Set<OsmGpxReceipt> = emptySet(),
    ) = prepareConfirmationForKeys(traces.map { OsmGpxReceiptKey.of(it) }, reviewedUnknown)

    private suspend fun OsmGpxController.receipts(traces: List<PreparedOsmGpxTrace>) =
        receiptsForKeys(traces.map { OsmGpxReceiptKey.of(it) })

    private suspend fun OsmGpxController.upload(
        confirmation: OsmGpxConfirmation,
        stillForeground: () -> Boolean,
        onAttempt: (OsmGpxReceiptKey, Int) -> Unit = { _, _ -> },
    ) = upload(confirmation, stillForeground, prepare = { tracesByKey[it] }, onAttempt = onAttempt)

    private fun session(id: Long, generation: Long = 2) =
        CommunitySession("fixture", id, "Fixture user", setOf("read_gpx", OSM_GPX_WRITE_SCOPE), generation)
}
