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
    suspend fun query(query: String, endpoint: String): String {
        val context = coroutineContext
        context.ensureActive()
        val started = now()
        var budget = readBudget()
        if (!budget.canRequest(started)) throw OsmServiceException("Harita sorgu sınırı veya servis bekleme süresi dolmadı.")
        val maxResponseBytes = minOf(512_000, 9_000_000 - budget.currentBytes(started))
        budget = budget.startRequest(started, reservedBytes = maxResponseBytes)
        check(writeBudget(budget)) { "Harita sorgu sınırı kaydedilemedi." }
        val request = Request.Builder().url(endpoint).header("User-Agent", osmUserAgent)
            .header("Accept", "application/json").header("Accept-Language", "tr")
            .post(FormBody.Builder().add("data", query).build()).build()
        var downloaded = 0L
        try {
            context.ensureActive()
            return execute(request).use { response ->
                context.ensureActive()
                val coolingDown = response.code == 429 || response.code == 406
                if (coolingDown) {
                    budget = budget.withCooldown(now(), retryAfterMillis(response) / 1_000)
                    check(writeBudget(budget)) { "Harita bekleme süresi kaydedilemedi." }
                }
                val json = readLimited(response, maxResponseBytes) { downloaded += it; context.ensureActive() }
                if (coolingDown) throw OsmServiceException("Harita servisi beklememizi istiyor.")
                if (!response.isSuccessful) throw OsmServiceException("Harita verisi alınamadı (${response.code}).")
                context.ensureActive()
                json
            }
        } finally {
            budget = budget.finishResponse(maxResponseBytes, downloaded)
            check(writeBudget(budget)) { "Harita sorgu sınırı kaydedilemedi." }
        }
    }
}
