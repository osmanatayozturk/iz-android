package org.iz.navigation.integration

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NearbyDiagnosticsTest {
    private val endpoint = "https://example.invalid/interpreter"
    private val secret = "SECRET https://private.invalid?token=private 39.12345,32.98765"
    private fun response(code: Int = 200, body: String = "{}") = Response.Builder()
        .request(Request.Builder().url(endpoint).build()).protocol(Protocol.HTTP_1_1)
        .code(code).message("SENSITIVE SERVER MESSAGE").body(body.toResponseBody()).build()

    @Test fun transportClassificationUsesTypesAndNeverRawMessages() {
        listOf(UnknownHostException(secret) to NearbyCode.ND_DNS,
            SSLHandshakeException(secret) to NearbyCode.ND_TLS,
            ConnectException(secret) to NearbyCode.ND_CONNECTION,
            SocketTimeoutException(secret) to NearbyCode.ND_NETWORK_TIMEOUT,
            SecurityException(secret) to NearbyCode.ND_ACCESS_DENIED).forEach { (error, code) ->
            val failure = NearbyProbe().failure(error)
            assertEquals(code, failure.code)
            assertFalse(failure.toString().contains(secret))
            assertFalse(failure.javaClass.declaredFields.any { Throwable::class.java.isAssignableFrom(it.type) })
        }
    }

    @Test fun localQueryBytesAndCooldownLimitsAreDistinctWithoutExecutingRequest() = runBlocking {
        listOf(OsmReadBudget(queries = 90) to NearbyLimit.QUERY_COUNT,
            OsmReadBudget(bytes = 9_000_000) to NearbyLimit.DAILY_BYTES,
            OsmReadBudget(cooldownUntil = 31_000) to NearbyLimit.SERVICE_WAIT).forEach { (initial, limit) ->
            var calls = 0
            val probe = NearbyProbe()
            val client = OverpassRead({ initial }, { fail("Blocked request must not write"); false },
                { calls++; response() }, { 1_000 })
            val error = assertThrows(OsmServiceException::class.java) { runBlocking { client.query(secret, endpoint, probe) } }
            val failure = probe.failure(error)
            assertEquals(NearbyStage.LIMITS, failure.stage)
            assertEquals(listOf(limit), failure.limits)
            assertEquals(if (limit == NearbyLimit.SERVICE_WAIT) NearbyCode.ND_SERVICE_WAIT else NearbyCode.ND_LOCAL_LIMIT, failure.code)
            assertEquals(0, calls)
            if (limit == NearbyLimit.SERVICE_WAIT) assertEquals(31_000L, failure.waitUntilMillis)
            assertFalse(failure.toString().contains(secret))
        }
    }

    @Test fun budgetReadFailureIsLocalStateEvenWhenCallerWasLoadingCatalogue() = runBlocking {
        val probe = NearbyProbe().apply { stage = NearbyStage.CATALOGUE }
        var calls = 0
        val client = OverpassRead({ throw IllegalStateException(secret) }, { fail("No write expected"); false },
            { calls++; response() }, { 1_000 })
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { client.query(secret, endpoint, probe) } }
        assertEquals(NearbyCode.ND_LOCAL_STATE, probe.failure(error).code)
        assertEquals(NearbyStage.LIMITS, probe.failure(error).stage)
        assertEquals(0, calls)
    }

    @Test fun observingHttpFailurePreservesExactRequestAndBudgetWrites() = runBlocking {
        suspend fun attempt(probe: NearbyProbe?): Pair<List<String>, OsmReadBudget> {
            var budget = OsmReadBudget()
            val observations = mutableListOf<String>()
            val client = OverpassRead({ budget }, { budget = it; observations += "budget:${it.toJson()}"; true }, {
                observations += "${it.method}:${it.url}:${Buffer().also { buffer -> it.body!!.writeTo(buffer) }.readUtf8()}"
                response(503, "unavailable")
            }, { 1_000 })
            assertThrows(OsmServiceException::class.java) { runBlocking { client.query(secret, endpoint, probe) } }
            return observations to budget
        }
        val original = attempt(null)
        val probe = NearbyProbe()
        assertEquals(original, attempt(probe))
        assertEquals(NearbyCode.ND_HTTP_REJECTED, probe.failure(Exception(secret)).code)
        assertEquals(503, probe.failure(Exception(secret)).httpStatus)
        assertEquals(1, original.first.count { it.startsWith("POST:") })
    }

    @Test fun serviceWaitAndResponseBoundKeepTheirExistingPolicies() = runBlocking {
        var budget = OsmReadBudget()
        val cooling = NearbyProbe()
        val client = OverpassRead({ budget }, { budget = it; true }, {
            response(429).newBuilder().header("Retry-After", "45").build()
        }, { 1_000 })
        val error = assertThrows(OsmServiceException::class.java) { runBlocking { client.query("query", endpoint, cooling) } }
        val wait = cooling.failure(error)
        assertEquals(NearbyCode.ND_SERVICE_WAIT, wait.code)
        assertEquals(429, wait.httpStatus)
        assertEquals(46_000L, wait.waitUntilMillis)
        assertEquals(46_000L, budget.cooldownUntil)
        val bounded = NearbyProbe()
        assertThrows(OsmServiceException::class.java) { readLimitedWithProbe(response(body = "1234"), 3, bounded) {} }
        assertEquals(NearbyCode.ND_RESPONSE_LIMIT, bounded.failure(Exception()).code)
    }

    @Test fun responseAndCancellationClassificationNeverRetainPayload() {
        val probe = NearbyProbe().apply { stage = NearbyStage.PARSER }
        assertEquals(NearbyCode.ND_INVALID_RESPONSE, probe.failure(IllegalArgumentException(secret)).code)
        val catalogue = NearbyProbe().apply { stage = NearbyStage.CATALOGUE }
        assertEquals(NearbyCode.ND_CATALOGUE, catalogue.failure(IllegalStateException(secret)).code)
        val cancelled = CancellationException(secret)
        assertSame(cancelled, assertThrows(CancellationException::class.java) { probe.failure(cancelled) })
        val diagnostic = NearbyDiagnostic(NearbyCode.ND_SERVICE_WAIT, NearbyStage.HTTP, 120,
            httpStatus = 429, waitUntilMillis = 46_000)
        assertEquals(30_000L, diagnostic.remainingWaitMillis(16_000))
        assertEquals(0L, diagnostic.remainingWaitMillis(46_001))
    }

    @Test fun parserBoundsAndServiceRemarkHaveFixedCodesWithoutPayload() {
        fun code(body: String): NearbyFailure {
            val error = assertThrows(Exception::class.java) { parseOverpassMapCandidates(body) }
            val failure = NearbyProbe().apply { stage = NearbyStage.PARSER }.failure(error)
            assertFalse(failure.toString().contains(secret))
            return failure
        }
        assertEquals(NearbyCode.ND_INVALID_RESPONSE, code(secret).code)
        assertEquals(NearbyCode.ND_SERVICE_ERROR, code("""{"remark":"$secret"}""").code)
        assertEquals(NearbyCode.ND_OBJECT_LIMIT, code("""{"elements":[${List(2001) { "{}" }.joinToString()}]}""").code)
        assertEquals(NearbyCode.ND_RESPONSE_LIMIT, code(" ".repeat(512_001)).code)
        // Existing successful-empty and invalid-coordinate filtering policies stay intact.
        assertTrue(parseOverpassMapCandidates("{}").isEmpty())
        assertTrue(parseOverpassMapCandidates("""{"elements":[{"type":"node","id":1,"tags":{"shop":"bakery"}}]}""").isEmpty())
    }

    @Test fun simultaneousLocalLimitsAreAllReportedAndDailyResetIsUnchanged() = runBlocking {
        val probe = NearbyProbe()
        val client = OverpassRead({ OsmReadBudget(queries = 90, bytes = 9_000_000, cooldownUntil = 31_000) },
            { fail("No write"); false }, { fail("No network"); response() }, { 1_000 })
        val error = assertThrows(OsmServiceException::class.java) { runBlocking { client.query("query", endpoint, probe) } }
        val failure = probe.failure(error)
        assertEquals(NearbyCode.ND_LOCAL_LIMIT, failure.code)
        assertEquals(listOf(NearbyLimit.QUERY_COUNT, NearbyLimit.DAILY_BYTES, NearbyLimit.SERVICE_WAIT), failure.limits)
        assertEquals(0, OsmReadBudget(queries = 90).currentQueries(86_400_000))
    }

    @Test fun transportFailureIsOneAttemptAndHttp200ParserFailureKeepsStatus() = runBlocking {
        var budget = OsmReadBudget()
        var calls = 0
        val transport = NearbyProbe()
        val client = OverpassRead({ budget }, { budget = it; true }, {
            calls++; throw UnknownHostException(secret)
        }, { 1_000 })
        val error = assertThrows(UnknownHostException::class.java) { runBlocking { client.query("query", endpoint, transport) } }
        assertEquals(1, calls)
        assertEquals(1, budget.queries)
        assertEquals(0L, budget.bytes)
        assertEquals(NearbyCode.ND_DNS, transport.failure(error).code)
        val parser = NearbyProbe()
        val ok = OverpassRead({ budget }, { budget = it; true }, { response(body = """{"remark":"$secret"}""") }, { 1_000 })
        val body = ok.query("query", endpoint, parser)
        parser.stage = NearbyStage.PARSER
        val invalid = assertThrows(NearbyResponseException::class.java) { parseOverpassMapCandidates(body) }
        assertEquals(NearbyCode.ND_SERVICE_ERROR, parser.failure(invalid).code)
        assertEquals(200, parser.failure(invalid).httpStatus)
        assertNull(NearbyFailureException(parser.failure(invalid)).cause)
    }
}
