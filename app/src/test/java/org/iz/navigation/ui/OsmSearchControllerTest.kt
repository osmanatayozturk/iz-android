package org.iz.navigation.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.integration.OsmServiceException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class OsmSearchControllerTest {
    private val buildingA = SelectedOsmPlace("Örnek bina", 40.0, 30.0, OsmRef(OsmType.WAY, 101))
    private val buildingB = SelectedOsmPlace("Örnek bina", 40.001, 30.001, OsmRef(OsmType.WAY, 102))

    @Test fun typingDoesNotSearchAndExplicitSearchPreservesAllBuildingObjects() = runTest {
        val queries = mutableListOf<String>()
        val controller = OsmSearchController(backgroundScope, "") { query ->
            queries += query; listOf(buildingA, buildingB)
        }
        controller.edit("ilk"); controller.edit("  bina  "); runCurrent()
        assertTrue(queries.isEmpty())
        controller.submit(); runCurrent()
        assertEquals(listOf("bina"), queries)
        assertEquals(2, controller.state.value.results.size)
        assertSame(buildingA, controller.state.value.results[0])
        assertSame(buildingB, controller.state.value.results[1])
        assertTrue(controller.state.value.searched)
        assertFalse(controller.state.value.busy)
    }

    @Test fun editingCompletedSearchClearsResultsAndSearchedState() = runTest {
        val controller = OsmSearchController(backgroundScope, "ilk") { listOf(buildingA) }
        controller.submit(); runCurrent()
        controller.edit("yeni")
        assertTrue(controller.state.value.results.isEmpty())
        assertFalse(controller.state.value.searched)
        assertNull(controller.state.value.error)
    }

    @Test fun editingEmptySearchClearsNotFoundState() = runTest {
        val controller = OsmSearchController(backgroundScope, "ilk") { emptyList() }
        controller.submit(); runCurrent()
        assertTrue(controller.state.value.searched)
        controller.edit("yeni")
        assertFalse(controller.state.value.searched)
    }

    @Test fun editingFailedSearchClearsError() = runTest {
        val controller = OsmSearchController(backgroundScope, "ilk") { throw IllegalStateException("Örnek hata") }
        controller.submit(); runCurrent()
        assertEquals("Arama tamamlanamadı. Biraz sonra yeniden dene.", controller.state.value.error)
        controller.edit("yeni")
        assertNull(controller.state.value.error)
    }

    @Test fun editCancelsInFlightSearchAndRejectsLateResultWithoutAnotherSubmit() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.edit("yeni")
        val cancelled = pending.calls[0].job.isCancelled
        val edited = controller.state.value
        pending.success(0, buildingA); runCurrent()
        assertTrue(cancelled)
        assertFalse(edited.busy)
        assertEquals("yeni", controller.state.value.query)
        assertTrue(controller.state.value.results.isEmpty())
        assertFalse(controller.state.value.searched)
    }

    @Test fun lateOldResultCannotFinishNewSearchEvenWhenQueryReturnsToSameText() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.edit("başka"); controller.edit("ilk"); controller.submit(); runCurrent()
        pending.success(0, buildingA); runCurrent()
        val duringNew = controller.state.value
        // The old implementation blocks the new submit, but the test still releases every request.
        if (pending.calls.size > 1) { pending.success(1, buildingB); runCurrent() }
        assertEquals(2, pending.calls.size)
        assertTrue(duringNew.busy)
        assertTrue(duringNew.results.isEmpty())
        assertSame(buildingB, controller.state.value.results.single())
    }

    @Test fun lateOldErrorCannotOverwriteNewSuccessfulSearch() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.edit("yeni"); controller.submit(); runCurrent()
        if (pending.calls.size > 1) { pending.success(1, buildingB); runCurrent() }
        pending.failure(0); runCurrent()
        assertEquals(2, pending.calls.size)
        assertNull(controller.state.value.error)
        assertSame(buildingB, controller.state.value.results.single())
        assertFalse(controller.state.value.busy)
    }

    @Test fun lateOldErrorAndFinallyCannotResetNewSearchProgress() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.edit("yeni"); controller.submit(); runCurrent()
        pending.failure(0); runCurrent()
        val duringNew = controller.state.value
        if (pending.calls.size > 1) { pending.success(1, buildingB); runCurrent() }
        assertEquals(2, pending.calls.size)
        assertTrue(duringNew.busy)
        assertNull(duringNew.error)
    }

    @Test fun closingDialogRejectsEvenNonCooperativeCompletion() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.close()
        val cancelled = pending.calls[0].job.isCancelled
        pending.success(0, buildingA); runCurrent()
        assertTrue(cancelled)
        assertTrue(controller.state.value.results.isEmpty())
        assertFalse(controller.state.value.searched)
        assertFalse(controller.state.value.busy)
    }

    @Test fun unchangedTextAndRepeatedSubmitDoNotCancelOrDuplicateCurrentSearch() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.edit("ilk"); controller.submit(); runCurrent()
        val cancelled = pending.calls[0].job.isCancelled
        pending.success(0, buildingA); runCurrent()
        assertFalse(cancelled)
        assertEquals(1, pending.calls.size)
        assertSame(buildingA, controller.state.value.results.single())
    }

    @Test fun blankQueryDoesNotSearchAndCancellationDoesNotBecomeAnError() = runTest {
        var calls = 0
        val controller = OsmSearchController(backgroundScope, "  ") { calls++; throw CancellationException() }
        controller.submit(); runCurrent()
        assertEquals(0, calls)
        controller.edit("ilk"); controller.submit(); runCurrent()
        assertEquals(1, calls)
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.busy)
        assertFalse(controller.state.value.searched)
    }

    @Test fun dnsFailureFor146CharacterQueryHidesRaw89CharacterDiagnostic() {
        val raw = "Unable to resolve host nominatim.invalid: No address associated with hostname".padEnd(89, '!')
        assertEquals(89, raw.length)
        assertErrorMessages(listOf(ErrorCase("DNS", UnknownHostException(raw), connectionMessage)), "a".repeat(146))
    }

    @Test fun recognizedConnectionFailuresUseApprovedConnectionMessage() = assertErrorMessages(listOf(
        ErrorCase("connect", ConnectException("connect failed: synthetic.invalid:443"), connectionMessage),
        ErrorCase("no route", NoRouteToHostException("No route to synthetic.invalid"), connectionMessage),
        ErrorCase("socket", SocketException("Connection reset at synthetic.invalid"), connectionMessage),
        ErrorCase("socket timeout", SocketTimeoutException("Read timed out for https://synthetic.invalid/search"), connectionMessage),
    ))

    @Test fun overlongQueryPreservesControlledValidationWithoutChangingSearchPolicy() = assertErrorMessages(
        listOf(ErrorCase("201-character validation", IllegalArgumentException(validationMessage), validationMessage)),
        "a".repeat(201),
    )

    @Test fun knownServiceAndPersistenceMessagesRemainExact() = assertErrorMessages(listOf(
        ErrorCase("request timestamp", IllegalStateException("Arama sınırı kaydedilemedi."), "Arama sınırı kaydedilemedi."),
        ErrorCase("cooldown timestamp", IllegalStateException("Arama bekleme süresi kaydedilemedi."), "Arama bekleme süresi kaydedilemedi."),
        // NominatimSearch uses this same controlled exception for HTTP429, HTTP406 and active cooldown.
        ErrorCase("429/406/cooldown", OsmServiceException(cooldownMessage), cooldownMessage),
        ErrorCase("empty body", OsmServiceException("Servis boş yanıt verdi."), "Servis boş yanıt verdi."),
        ErrorCase("response byte limit", OsmServiceException("Servis yanıtı boyut sınırını aştı."), "Servis yanıtı boyut sınırını aştı."),
    ))

    @Test fun knownHttpTemplatePreservesOnlyTheBoundedStatusCode() = assertErrorMessages(
        listOf(100, 199, 300, 400, 403, 404, 500, 503, 599).map { code ->
            val message = "Yer araması şu an kullanılamıyor ($code)."
            ErrorCase("HTTP$code", OsmServiceException(message), message)
        },
    )

    @Test fun unknownTypesMessagesAndWrappersUseGenericText() {
        val raw = "https://synthetic.invalid/search?private=qa-marker\n at synthetic.stack.Trace".repeat(5)
        assertErrorMessages(listOf(
            ErrorCase("unknown", Exception(raw), genericMessage),
            ErrorCase("null", Exception(), genericMessage),
            ErrorCase("unknown IO", IOException(raw), genericMessage),
            ErrorCase("interrupted IO", InterruptedIOException(raw), genericMessage),
            ErrorCase("TLS", SSLException(raw), genericMessage),
            ErrorCase("unknown validation", IllegalArgumentException(raw), genericMessage),
            ErrorCase("unknown state", IllegalStateException("Türkçe olması güvenilir kılmaz: $raw"), genericMessage),
            ErrorCase("unknown service", OsmServiceException(raw), genericMessage),
            ErrorCase("wrapped DNS", RuntimeException(raw, UnknownHostException(raw)), genericMessage),
            ErrorCase("wrong validation type", IllegalStateException(validationMessage), genericMessage),
            ErrorCase("wrong persistence type", IllegalArgumentException("Arama sınırı kaydedilemedi."), genericMessage),
            ErrorCase("wrong cooldown type", IOException(cooldownMessage), genericMessage),
            ErrorCase("wrong HTTP type", IllegalStateException("Yer araması şu an kullanılamıyor (503)."), genericMessage),
            ErrorCase("validation in service type", OsmServiceException(validationMessage), genericMessage),
        ))
    }

    @Test fun alteredKnownMessagesAndHttpTemplatesCannotExposeRawDetails() = assertErrorMessages(
        listOf(
            OsmServiceException("Yer araması şu an kullanılamıyor (99)."),
            OsmServiceException("Yer araması şu an kullanılamıyor (600)."),
            OsmServiceException("Yer araması şu an kullanılamıyor (0500)."),
            OsmServiceException("Yer araması şu an kullanılamıyor (500). https://synthetic.invalid"),
            OsmServiceException("Yer araması şu an kullanılamıyor (500).\n"),
            OsmServiceException("prefix Yer araması şu an kullanılamıyor (500)."),
            OsmServiceException(" $cooldownMessage"),
            OsmServiceException("$cooldownMessage\nraw stack"),
            IllegalArgumentException("$validationMessage "),
            IllegalStateException("Arama sınırı kaydedilemedi. raw details"),
        ).mapIndexed { index, failure -> ErrorCase("altered controlled message $index", failure, genericMessage) },
    )

    private data class ErrorCase(val label: String, val failure: Exception, val expected: String)

    private fun assertErrorMessages(cases: List<ErrorCase>, query: String = "bina") = runTest {
        for ((label, failure, expected) in cases) {
            val queries = mutableListOf<String>()
            val controller = OsmSearchController(backgroundScope, query) { submitted ->
                queries += submitted
                throw failure
            }
            controller.submit()
            runCurrent()
            assertEquals(label, expected, controller.state.value.error)
            assertEquals("$label must not retry or rewrite the submitted query", listOf(query), queries)
            assertEquals(label, query, controller.state.value.query)
            assertFalse("$label must release busy", controller.state.value.busy)
            assertFalse("$label is an error, not an empty successful search", controller.state.value.searched)
            assertTrue(label, controller.state.value.results.isEmpty())
            controller.edit("changed")
            runCurrent()
            assertNull("$label must clear on edit", controller.state.value.error)
            assertEquals("$label must not search on edit", 1, queries.size)
        }
    }

    private val connectionMessage = "Arama servisine ulaşılamadı. Bağlantını kontrol edip yeniden ara."
    private val genericMessage = "Arama tamamlanamadı. Biraz sonra yeniden dene."
    private val validationMessage = "Arama en fazla 200 karakter olabilir."
    private val cooldownMessage = "Yer arama servisi beklememizi istiyor. Biraz sonra yeniden ara."

    // A controlled external boundary that intentionally ignores cancellation, as late I/O can do.
    private class PendingSearch {
        data class Call(val query: String, val job: Job, val continuation: Continuation<List<SelectedOsmPlace>>)
        val calls = mutableListOf<Call>()
        suspend fun search(query: String): List<SelectedOsmPlace> {
            val job = currentCoroutineContext().job
            return suspendCoroutine { calls += Call(query, job, it) }
        }
        fun success(index: Int, place: SelectedOsmPlace) = calls[index].continuation.resume(listOf(place))
        fun failure(index: Int) = calls[index].continuation.resumeWithException(IllegalStateException("Eski arama hatası"))
    }
}
