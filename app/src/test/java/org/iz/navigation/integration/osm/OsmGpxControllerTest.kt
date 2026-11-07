package org.iz.navigation.integration.osm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val second = osmGpxTestTrace("journey-second")
    private val third = osmGpxTestTrace("journey-third")

    @Test fun recordsPartialSuccessAndRequiresExactUnknownReviewBeforeFreshConsent() = runBlocking {
        val calls = mutableListOf<PreparedOsmGpxTrace>()
        val attempts = mutableListOf<Pair<OsmGpxReceiptKey, Int>>()
        val controller = controller { trace ->
            calls += trace
            if (calls.size == 2) OsmGpxUploadResult.Unknown else OsmGpxUploadResult.Received(123)
        }
        val traces = listOf(first, second, third)
        val confirmation = checkNotNull(controller.prepareConfirmation(traces))
        assertSame(first, confirmation.traces.first())
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
        assertEquals(listOf(second), retry.traces)
        assertEquals(OsmGpxStop.COMPLETE, controller.upload(retry, { true }).stop)
        assertNull(controller.prepareConfirmation(listOf(first, second), setOf(unknown)))
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
        val confirmation = checkNotNull(controller.prepareConfirmation(listOf(first)))
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
        val firstConsent = checkNotNull(controller.prepareConfirmation(listOf(first)))
        assertSame(sessions.value, controller.sessions.value)
        sessions.value = session(42, generation = 3)
        assertEquals(3L, controller.sessions.value?.generation)
        assertEquals(OsmGpxStop.ACCOUNT_CHANGED, controller.upload(firstConsent, { true }).stop)
        assertFalse(controller.receipts(listOf(first)).containsKey(OsmGpxReceiptKey.of(first)))
    }

    private fun controller(upload: suspend (PreparedOsmGpxTrace) -> OsmGpxUploadResult) = OsmGpxController(
        currentSession = { sessions.value },
        gateway = OsmGpxGateway { _, trace, canDispatch ->
            check(canDispatch())
            upload(trace)
        },
        store = OsmGpxFileReceipts(noBackup.root),
        authState = MutableStateFlow(OsmAuthUiState()),
        sessions = sessions,
        permissionRequest = {},
    )

    private fun session(id: Long, generation: Long = 2) =
        CommunitySession("fixture", id, "Fixture user", setOf(OSM_GPX_WRITE_SCOPE), generation)
}
