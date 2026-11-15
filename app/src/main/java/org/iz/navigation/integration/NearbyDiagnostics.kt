package org.iz.navigation.integration

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/** Fixed vocabulary only. No request, endpoint, coordinates, payload or Throwable is retained. */
internal enum class NearbyCode {
    ND_LOCAL_LIMIT, ND_SERVICE_WAIT, ND_LOCAL_STATE, ND_DNS, ND_TLS, ND_CONNECTION,
    ND_ACCESS_DENIED, ND_NETWORK_TIMEOUT, ND_DISCOVERY_TIMEOUT, ND_HTTP_REJECTED,
    ND_INVALID_RESPONSE, ND_MISSING_RESPONSE, ND_RESPONSE_LIMIT, ND_OBJECT_LIMIT,
    ND_SERVICE_ERROR, ND_CATALOGUE, ND_UNKNOWN,
}
internal enum class NearbyStage { PREPARATION, LIMITS, NETWORK, HTTP, RESPONSE, PARSER, CATALOGUE, DISCOVERY }
internal enum class NearbyLimit { QUERY_COUNT, DAILY_BYTES, SERVICE_WAIT, RESPONSE_BYTES, OBJECT_COUNT }

internal data class NearbyFailure(val code: NearbyCode, val stage: NearbyStage,
    val httpStatus: Int? = null, val waitUntilMillis: Long? = null,
    val limits: List<NearbyLimit> = emptyList())

internal data class NearbyDiagnostic(val code: NearbyCode, val stage: NearbyStage, val elapsedMillis: Long,
    val httpStatus: Int? = null, val waitUntilMillis: Long? = null,
    val limits: List<NearbyLimit> = emptyList()) {
    fun remainingWaitMillis(now: Long): Long {
        val deadline = waitUntilMillis ?: return 0
        if (deadline <= now) return 0
        val remaining = deadline - now
        return if (remaining < 0) 86_400_000 else remaining.coerceAtMost(86_400_000)
    }
}

internal fun NearbyFailure.diagnostic(elapsedMillis: Long) = NearbyDiagnostic(code, stage,
    elapsedMillis.coerceAtLeast(0), httpStatus, waitUntilMillis, limits.toList())

/** Sanitized carrier used only at the nearby boundary; the original exception is not its cause. */
internal class NearbyFailureException(val failure: NearbyFailure) : IOException(failure.code.name)
internal class NearbyResponseException(val failure: NearbyFailure, message: String) : IllegalArgumentException(message)

/** One invocation owns this observer. It never changes request, budget, cache or cancellation policy. */
internal class NearbyProbe {
    var stage = NearbyStage.NETWORK
    var httpStatus: Int? = null
    var responseLimit = NearbyLimit.RESPONSE_BYTES
    private var recorded: NearbyFailure? = null

    fun fail(code: NearbyCode, stage: NearbyStage = this.stage, waitUntilMillis: Long? = null,
        limits: List<NearbyLimit> = emptyList()) {
        recorded = NearbyFailure(code, stage, httpStatus?.takeIf { it in 100..599 }, waitUntilMillis, limits.toList())
    }

    fun failure(error: Exception): NearbyFailure {
        if (error is CancellationException) throw error
        val known = when (error) {
            is NearbyFailureException -> error.failure
            is NearbyResponseException -> error.failure
            else -> recorded
        }
        if (known != null) return known.copy(httpStatus = known.httpStatus ?: httpStatus?.takeIf { it in 100..599 })
        if (stage == NearbyStage.LIMITS) return NearbyFailure(NearbyCode.ND_LOCAL_STATE, stage,
            httpStatus?.takeIf { it in 100..599 })
        var cause: Throwable? = error
        repeat(8) {
            val code = when (cause) {
                is UnknownHostException -> NearbyCode.ND_DNS
                is SSLException -> NearbyCode.ND_TLS
                is SocketTimeoutException, is InterruptedIOException -> NearbyCode.ND_NETWORK_TIMEOUT
                is ConnectException, is NoRouteToHostException -> NearbyCode.ND_CONNECTION
                is SecurityException -> NearbyCode.ND_ACCESS_DENIED
                else -> null
            }
            if (code != null) return NearbyFailure(code, stage, httpStatus?.takeIf { it in 100..599 })
            cause = cause?.cause
        }
        val code = when (stage) {
            NearbyStage.CATALOGUE -> NearbyCode.ND_CATALOGUE
            NearbyStage.PARSER -> NearbyCode.ND_INVALID_RESPONSE
            NearbyStage.NETWORK, NearbyStage.RESPONSE -> if (error is IOException) NearbyCode.ND_CONNECTION else NearbyCode.ND_UNKNOWN
            else -> NearbyCode.ND_UNKNOWN
        }
        return NearbyFailure(code, stage, httpStatus?.takeIf { it in 100..599 })
    }
}
