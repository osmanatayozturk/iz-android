package org.iz.navigation.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.iz.navigation.data.SelectedOsmPlace
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Search-only flow; the caller supplies the shared process gate and persisted storage. */
internal class NominatimSearch(
    private val mutex: Mutex,
    private val endpoint: () -> String,
    private val readCached: (String) -> List<SelectedOsmPlace>?,
    private val writeCache: (String, String) -> Unit,
    private val readLong: (String) -> Long,
    private val writeLong: (String, Long) -> Boolean,
    private val execute: (Request) -> Response,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun search(query: String): List<SelectedOsmPlace> {
        coroutineContext.ensureActive()
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.length < 2) return emptyList()
        require(normalized.length <= 200) { "Arama en fazla 200 karakter olabilir." }
        return mutex.withLock {
            coroutineContext.ensureActive()
            val service = endpoint().toHttpUrl()
            val first = page(searchUrl(service, normalized))
            val exclusions = first.mapNotNull { it.osmRef }
                .map { it.type.name.first().toString() + it.id }.distinct().sorted().joinToString(",")
            // One bounded continuation of this explicit submission, never an all-results loop.
            val extra = if (first.size in 1..9 && exclusions.isNotEmpty()) {
                try {
                    page(searchUrl(service, normalized, exclusions))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed optional page cannot discard a valid first page or hide cancellation.
                    coroutineContext.ensureActive()
                    emptyList()
                }
            } else emptyList()
            coroutineContext.ensureActive()
            (first + extra).distinctBy { it.osmRef ?: Triple(it.name, it.latitude, it.longitude) }.take(20)
        }
    }

    private fun searchUrl(service: HttpUrl, query: String, exclusions: String = ""): HttpUrl =
        service.newBuilder()
            .setQueryParameter("q", query).setQueryParameter("format", "jsonv2")
            .setQueryParameter("limit", "10").setQueryParameter("addressdetails", "0")
            .removeAllQueryParameters("exclude_place_ids")
            .apply { if (exclusions.isNotEmpty()) setQueryParameter("exclude_place_ids", exclusions) }
            .build()

    private suspend fun page(url: HttpUrl): List<SelectedOsmPlace> {
        val context = coroutineContext
        context.ensureActive()
        // Version and full request options isolate old one-page data and each exclusion page.
        val cacheUrl = url.newBuilder()
            .setQueryParameter("q", url.queryParameter("q")!!.lowercase(Locale.ROOT)).build()
        val key = "search-v2:accept-language=tr:$cacheUrl"
        val cached = readCached(key)
        context.ensureActive()
        if (cached != null) return cached.take(10)

        checkCooldown()
        val wait = (readLong("nominatim_last_request") + 1_000 - now()).coerceIn(0, 1_000)
        if (wait > 0) pause(wait)
        context.ensureActive()
        checkCooldown()
        check(writeLong("nominatim_last_request", now())) { "Arama sınırı kaydedilemedi." }
        context.ensureActive()
        val request = Request.Builder().url(url).header("User-Agent", osmUserAgent)
            .header("Accept", "application/json").header("Accept-Language", "tr").get().build()
        execute(request).use { response ->
            context.ensureActive()
            if (response.code == 429 || response.code == 406) {
                check(writeLong("nominatim_cooldown", now() + retryAfterMillis(response))) { "Arama bekleme süresi kaydedilemedi." }
                context.ensureActive()
                throw OsmServiceException("Yer arama servisi beklememizi istiyor. Biraz sonra yeniden ara.")
            }
            if (!response.isSuccessful) throw OsmServiceException("Yer araması şu an kullanılamıyor (" + response.code + ").")
            val json = readLimited(response, 512_000) { context.ensureActive() }
            context.ensureActive()
            val result = parseNominatimPlaces(json).take(10)
            context.ensureActive()
            writeCache(key, json)
            context.ensureActive()
            return result
        }
    }

    private fun checkCooldown() {
        if (now() < readLong("nominatim_cooldown")) {
            throw OsmServiceException("Yer arama servisi beklememizi istiyor. Biraz sonra yeniden ara.")
        }
    }
}