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
import org.json.JSONArray
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Search-only flow; the caller supplies the shared process gate and persisted storage. */
internal class NominatimSearch(
    private val mutex: Mutex,
    private val endpoint: () -> String,
    private val readCached: (String) -> String?,
    private val writeCache: (String, String) -> Unit,
    private val readLong: (String) -> Long,
    private val writeLong: (String, Long) -> Boolean,
    private val execute: (Request) -> Response,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun search(query: String): List<SelectedOsmPlace> = search(query, directions = false, viewbox = null) { emptyList() }

    suspend fun searchForDirections(
        query: String,
        viewbox: DirectionsViewbox?,
        nearby: suspend () -> List<SelectedOsmPlace> = { emptyList() },
    ): List<SelectedOsmPlace> = search(query, directions = true, viewbox = viewbox, nearby = nearby)

    private suspend fun search(query: String, directions: Boolean, viewbox: DirectionsViewbox?, nearby: suspend () -> List<SelectedOsmPlace>): List<SelectedOsmPlace> {
        coroutineContext.ensureActive()
        val normalized = normalizeNominatimQuery(query)
        if (normalized.length < 2) return emptyList()
        return mutex.withLock {
            coroutineContext.ensureActive()
            val configuredEndpoint = endpoint()
            fun checkCurrent() {
                if (endpoint() != configuredEndpoint) throw CancellationException("Arama servisi değişti; eski yanıt iptal edildi.")
            }
            val endpointUrl = configuredEndpoint.toHttpUrl()
            val service = if (directions) endpointUrl.newBuilder()
                .removeAllQueryParameters("viewbox")
                .removeAllQueryParameters("bounded")
                .apply {
                    if (viewbox != null) {
                        setQueryParameter("viewbox", viewbox.value)
                        setQueryParameter("bounded", "0")
                    }
                }.build() else endpointUrl
            val firstPage = page(searchUrl(service, normalized, directions = directions), directions, ::checkCurrent)
            val first = firstPage.places
            if (directions && !firstPage.matches(normalized)) {
                val fallback = if (viewbox != null && PartialNameMatcher(normalized).canSearchNearby) {
                    try {
                        coroutineContext.ensureActive()
                        checkCurrent()
                        nearby()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { coroutineContext.ensureActive(); emptyList() }
                } else emptyList()
                coroutineContext.ensureActive()
                checkCurrent()
                return@withLock fallback.ifEmpty { first }
            }
            val exclusions = first.mapNotNull { it.osmRef }
                .map { it.type.name.first().toString() + it.id }.distinct().sorted().joinToString(",")
            // One bounded continuation of this explicit submission, never an all-results loop.
            val extra = if (first.size in 1..9 && exclusions.isNotEmpty()) {
                try {
                    page(searchUrl(service, normalized, exclusions, directions), directions, ::checkCurrent).places
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed optional page cannot discard a valid first page or hide cancellation.
                    coroutineContext.ensureActive()
                    emptyList()
                }
            } else emptyList()
            coroutineContext.ensureActive()
            checkCurrent()
            (first + extra).distinctBy { it.osmRef ?: Triple(it.name, it.latitude, it.longitude) }.take(20)
        }
    }

    private fun searchUrl(service: HttpUrl, query: String, exclusions: String = "", directions: Boolean = false): HttpUrl =
        service.newBuilder()
            .setQueryParameter("q", query).setQueryParameter("format", "jsonv2")
            .setQueryParameter("limit", "10").setQueryParameter("addressdetails", "0")
            .removeAllQueryParameters("exclude_place_ids")
            .apply {
                if (exclusions.isNotEmpty()) setQueryParameter("exclude_place_ids", exclusions)
                if (directions) setQueryParameter("namedetails", "1")
            }
            .build()

    private data class Page(val places: List<SelectedOsmPlace>, val names: List<String>) {
        fun matches(query: String) = names.any(PartialNameMatcher(query)::matches)
    }

    private fun parsePage(json: String): Page {
        val array = JSONArray(json)
        val selected = (0 until array.length()).asSequence().mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val place = parseNominatimPlaces(JSONArray().put(item).toString()).firstOrNull() ?: return@mapNotNull null
            item to place
        }.take(10).toList()
        val names = selected.flatMap { (item, _) ->
            listOf(item.optString("name"), item.optString("display_name")) +
                partialNameTags.flatMap { key ->
                    (item.optJSONObject("namedetails")?.opt(key) as? String)?.split(';').orEmpty()
                }
        }
        return Page(selected.map { it.second }, names)
    }

    private suspend fun page(url: HttpUrl, directions: Boolean, checkCurrent: () -> Unit): Page {
        val context = coroutineContext
        context.ensureActive()
        // Version and full request options isolate old one-page data and each exclusion page.
        val key = if (directions) {
            "search-v4:accept-language=tr:$url"
        } else {
            val cacheUrl = url.newBuilder()
                .setQueryParameter("q", url.queryParameter("q")!!.lowercase(Locale.ROOT)).build()
            "search-v2:accept-language=tr:$cacheUrl"
        }
        val cached = readCached(key)?.let { runCatching { parsePage(it) }.getOrNull() }
        context.ensureActive()
        checkCurrent()
        if (cached != null) return cached

        checkCooldown()
        val wait = (readLong("nominatim_last_request") + 1_000 - now()).coerceIn(0, 1_000)
        if (wait > 0) pause(wait)
        context.ensureActive()
        checkCurrent()
        checkCooldown()
        check(writeLong("nominatim_last_request", now())) { "Arama sınırı kaydedilemedi." }
        context.ensureActive()
        val request = Request.Builder().url(url).header("User-Agent", osmUserAgent)
            .header("Accept", "application/json").header("Accept-Language", "tr").get().build()
        execute(request).use { response ->
            context.ensureActive()
            checkCurrent()
            if (response.code == 429 || response.code == 406) {
                check(writeLong("nominatim_cooldown", now() + retryAfterMillis(response))) { "Arama bekleme süresi kaydedilemedi." }
                context.ensureActive()
                throw OsmServiceException("Yer arama servisi beklememizi istiyor. Biraz sonra yeniden ara.")
            }
            if (!response.isSuccessful) throw OsmServiceException("Yer araması şu an kullanılamıyor (" + response.code + ").")
            val json = readLimited(response, 512_000) { context.ensureActive() }
            context.ensureActive()
            val result = parsePage(json)
            context.ensureActive()
            checkCurrent()
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

internal fun normalizeNominatimQuery(query: String): String {
    val normalized = query.trim().replace(Regex("\\s+"), " ")
    require(normalized.length <= 200) { "Arama en fazla 200 karakter olabilir." }
    return normalized
}
