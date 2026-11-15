package org.iz.navigation.integration

import kotlinx.coroutines.ensureActive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext

/** All callers hold the process Overpass mutex; reservations include failed and cancelled reads. */
internal class OverpassRead(
    private val readBudget: () -> OsmReadBudget,
    private val writeBudget: (OsmReadBudget) -> Boolean,
    private val execute: (Request) -> Response,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun query(query: String, endpoint: String, probe: NearbyProbe? = null): String {
        val context = coroutineContext
        context.ensureActive()
        val started = now()
        probe?.stage = NearbyStage.LIMITS
        var budget = readBudget()
        if (!budget.canRequest(started)) {
            val limits = buildList {
                if (budget.currentQueries(started) >= 90) add(NearbyLimit.QUERY_COUNT)
                if (budget.currentBytes(started) >= 9_000_000) add(NearbyLimit.DAILY_BYTES)
                if (started < budget.cooldownUntil) add(NearbyLimit.SERVICE_WAIT)
            }
            val code = if (limits == listOf(NearbyLimit.SERVICE_WAIT)) NearbyCode.ND_SERVICE_WAIT else NearbyCode.ND_LOCAL_LIMIT
            probe?.fail(code, NearbyStage.LIMITS,
                budget.cooldownUntil.takeIf { started < it }, limits)
            throw OsmServiceException("Harita sorgu sınırı veya servis bekleme süresi dolmadı.")
        }
        val maxResponseBytes = minOf(512_000, 9_000_000 - budget.currentBytes(started))
        budget = budget.startRequest(started, reservedBytes = maxResponseBytes)
        val saved = writeBudget(budget)
        if (!saved) probe?.fail(NearbyCode.ND_LOCAL_STATE, NearbyStage.LIMITS)
        check(saved) { "Harita sorgu sınırı kaydedilemedi." }
        probe?.stage = NearbyStage.PREPARATION
        val request = Request.Builder().url(endpoint).header("User-Agent", osmUserAgent)
            .header("Accept", "application/json").header("Accept-Language", "tr")
            .post(FormBody.Builder().add("data", query).build()).build()
        var downloaded = 0L
        try {
            context.ensureActive()
            probe?.stage = NearbyStage.NETWORK
            return execute(request).use { response ->
                context.ensureActive()
                probe?.httpStatus = response.code
                val coolingDown = response.code == 429 || response.code == 406
                if (coolingDown) {
                    budget = budget.withCooldown(now(), retryAfterMillis(response) / 1_000)
                    val cooldownSaved = try { writeBudget(budget) } catch (error: Exception) {
                        probe?.fail(NearbyCode.ND_LOCAL_STATE, NearbyStage.LIMITS)
                        throw error
                    }
                    if (!cooldownSaved) probe?.fail(NearbyCode.ND_LOCAL_STATE, NearbyStage.LIMITS)
                    check(cooldownSaved) { "Harita bekleme süresi kaydedilemedi." }
                }
                probe?.stage = NearbyStage.RESPONSE
                probe?.responseLimit = if (maxResponseBytes < 512_000) NearbyLimit.DAILY_BYTES else NearbyLimit.RESPONSE_BYTES
                val json = readLimitedWithProbe(response, maxResponseBytes, probe) { downloaded += it; context.ensureActive() }
                if (json.isBlank()) probe?.fail(NearbyCode.ND_MISSING_RESPONSE, NearbyStage.RESPONSE)
                if (coolingDown) {
                    probe?.fail(NearbyCode.ND_SERVICE_WAIT, NearbyStage.HTTP, budget.cooldownUntil,
                        listOf(NearbyLimit.SERVICE_WAIT))
                    throw OsmServiceException("Harita servisi beklememizi istiyor.")
                }
                if (!response.isSuccessful) {
                    probe?.fail(NearbyCode.ND_HTTP_REJECTED, NearbyStage.HTTP)
                    throw OsmServiceException("Harita verisi alınamadı (${response.code}).")
                }
                context.ensureActive()
                json
            }
        } finally {
            budget = budget.finishResponse(maxResponseBytes, downloaded)
            val finished = try { writeBudget(budget) } catch (error: Exception) {
                probe?.fail(NearbyCode.ND_LOCAL_STATE, NearbyStage.LIMITS)
                throw error
            }
            if (!finished) probe?.fail(NearbyCode.ND_LOCAL_STATE, NearbyStage.LIMITS)
            check(finished) { "Harita sorgu sınırı kaydedilemedi." }
        }
    }
}
