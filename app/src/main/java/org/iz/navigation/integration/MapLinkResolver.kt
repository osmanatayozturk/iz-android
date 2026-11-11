package org.iz.navigation.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.iz.navigation.car.SharedMapInput
import org.iz.navigation.car.SharedMapLinks
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class MapLinkResponse(val code: Int, val location: String?, val body: String, val bytes: Int)
internal fun interface MapLinkExchange {
    suspend fun request(url: String, addresses: List<InetAddress>, maxBytes: Int): MapLinkResponse
}

/** Isolated unauthenticated resolver: no shared OSM/auth interceptors, cookies, proxies or logging. */
class MapLinkResolver internal constructor(private val dns: Dns, private val exchange: MapLinkExchange,
    private val totalTimeoutMillis: Long = 10_000) {
    constructor() : this(Dns.SYSTEM, IsolatedMapLinkExchange)

    suspend fun resolve(raw: String): SharedMapInput? {
        val parsed = SharedMapLinks.parse(raw)
        if (parsed !is SharedMapInput.ShortLink) return parsed
        return try {
            withTimeout(totalTimeoutMillis) {
                withContext(Dispatchers.IO) {
                var current = parsed.url
                var remaining = 512 * 1024
                val seen = mutableSetOf<String>()
                var redirects = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val url = SharedMapLinks.allowedUrl(current) ?: return@withContext failed(parsed.url)
                    if (!seen.add(url.toASCIIString())) return@withContext failed(parsed.url)
                    val addresses = resolveMapDns(dns, url.host)
                    currentCoroutineContext().ensureActive()
                    if (addresses.isEmpty() || addresses.any { !isPublicMapAddress(it) }) return@withContext failed(parsed.url)
                    val local = SharedMapLinks.parse(current)
                    if (local !is SharedMapInput.ShortLink) return@withContext local ?: failed(parsed.url)
                    val result = exchange.request(current, addresses, remaining)
                    require(result.bytes >= 0 && result.bytes <= remaining)
                    remaining -= result.bytes
                    val context = currentCoroutineContext()
                    val target = if (result.code in setOf(301,302,303,307,308)) result.location else if (result.code in 200..299) canonical(result.body) { context.ensureActive() } else null
                    if (target == null || redirects >= 5) return@withContext failed(parsed.url)
                    val next = url.resolve(target).toASCIIString()
                    // Validate before even a DNS lookup or HTTP request to the next hop.
                    if (SharedMapLinks.allowedUrl(next) == null) return@withContext failed(parsed.url)
                    redirects++
                    current = next
                }
                @Suppress("UNREACHABLE_CODE") null
                }
            }
        } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); failed(parsed.url) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed(parsed.url) }
    }

    private fun failed(original: String) = SharedMapInput.Unsupported("Harita bağlantısı güvenli sınırlar içinde çözülemedi. Asıl uygulamada açabilir veya yer adıyla arayabilirsin.", original)
    private fun canonical(body: String, checkActive: () -> Unit): String? {
        // Parse only an explicit canonical URL; never run scripts or crawl arbitrary links.
        // Consume each tag once. Repeated unterminated '<meta' cannot restart a bounded regex
        // scan from every '<' in a512KiB body. Parsing remains cancellable and off the UI thread.
        var cursor = 0
        while (cursor < body.length) {
            checkActive()
            val open = body.indexOf('<', cursor).takeIf { it >= 0 } ?: return null
            if (body.startsWith("<!--", open)) {
                val close = body.indexOf("-->", open + 4).takeIf { it >= 0 } ?: return null
                cursor = close + 3
                continue
            }
            val end = body.indexOf('>', open + 1).takeIf { it >= 0 } ?: return null
            cursor = end + 1
            if (end - open > 4096) continue
            val tag = body.substring(open + 1, end)
            var index = tag.indexOfFirst { !it.isLetter() }.let { if (it < 0) tag.length else it }
            val kind = tag.substring(0, index).lowercase()
            if (kind in setOf("script", "style")) {
                cursor = body.indexOf("</$kind", end + 1, ignoreCase = true).takeIf { it >= 0 } ?: return null
                continue
            }
            if (kind !in setOf("link", "meta")) continue
            val attrs = mutableMapOf<String, String>()
            while (index < tag.length) {
                checkActive()
                while (index < tag.length && tag[index].isWhitespace()) index++
                val start = index
                while (index < tag.length && (tag[index].isLetterOrDigit() || tag[index] in "_:-")) index++
                if (index == start) { index++; continue }
                val name = tag.substring(start, index).lowercase()
                while (index < tag.length && tag[index].isWhitespace()) index++
                if (index >= tag.length || tag[index] != '=') continue
                index++
                while (index < tag.length && tag[index].isWhitespace()) index++
                if (index >= tag.length) break
                val quote = tag[index].takeIf { it == '\'' || it == '"' }
                val value = if (quote != null) {
                    val close = tag.indexOf(quote, index + 1).takeIf { it >= 0 } ?: break
                    tag.substring(index + 1, close).also { index = close + 1 }
                } else {
                    val from = index
                    while (index < tag.length && !tag[index].isWhitespace()) index++
                    tag.substring(from, index)
                }
                if (name in setOf("rel", "href", "property", "content")) attrs.putIfAbsent(name, value)
            }
            val target = when {
                kind == "link" && attrs["rel"]?.lowercase()?.split(Regex("\\s+"))?.contains("canonical") == true -> attrs["href"]
                kind == "meta" && attrs["property"]?.lowercase() == "og:url" -> attrs["content"]
                else -> null
            }
            if (target != null) return target.replace("&amp;", "&")
        }
        return null
    }
}

// Platform DNS may ignore interrupts. Never make UI cancellation wait for it and never spawn
// another unbounded IO worker on each timeout. At most two daemon lookups can remain in flight.
private val mapDnsWorkers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
    { task -> Thread(task, "iz-map-link-dns").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }

private suspend fun resolveMapDns(dns: Dns, hostname: String): List<InetAddress> = suspendCancellableCoroutine { continuation ->
    try {
        val task = mapDnsWorkers.submit {
            try {
                val addresses = dns.lookup(hostname)
                if (continuation.isActive) continuation.resume(addresses)
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Harita adresi çözülemedi."))
            }
        }
        continuation.invokeOnCancellation { task.cancel(true) }
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        if (continuation.isActive) continuation.resumeWithException(IOException("Harita adres çözümleme meşgul. Yeniden dene."))
    }
}

internal fun isPublicMapAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
    val b = address.address.map { it.toInt() and 255 }
    return if (b.size == 4) {
        !(b[0] in setOf(0,10,127) || b[0] >= 224 || b[0] == 100 && b[1] in 64..127 ||
            b[0] == 169 && b[1] == 254 || b[0] == 172 && b[1] in 16..31 ||
            b[0] == 192 && (b[1] == 168 || b[1] == 0 && b[2] in setOf(0,2) || b[1] == 88 && b[2] == 99) ||
            b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100) || b[0] == 203 && b[1] == 0 && b[2] == 113)
    } else b.size == 16 && b[0] in 0x20..0x3f && !(b[0] == 0x20 && b[1] == 0x02) &&
        !(b[0] == 0x20 && b[1] == 0x01 && b[2] in 0..1) && !(b.take(4) == listOf(0x20,0x01,0x0d,0xb8)) &&
        !(b[0] == 0x3f && b[1] == 0xff && b[2] < 16)
}

private object IsolatedMapLinkExchange : MapLinkExchange {
    override suspend fun request(url: String, addresses: List<InetAddress>, maxBytes: Int): MapLinkResponse = suspendCancellableCoroutine { continuation ->
        val host = java.net.URI(url).host.lowercase()
        // Pin the validated answers for this one call. A second DNS response cannot rebind it.
        // OkHttp 4.12 Dns contract: https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Dns.kt
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> { require(hostname == host); return addresses }
        }).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Iz/map-link-resolver").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Harita bağlantısı alınamadı."))
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val body = it.body
                        require(body == null || body.contentLength() <= maxBytes)
                        val bytes = ByteArrayOutputStream()
                        body?.byteStream()?.use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (!continuation.isActive) throw IOException("İptal edildi.")
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(bytes.size() + count <= maxBytes)
                                bytes.write(buffer, 0, count)
                            }
                        }
                        val value = MapLinkResponse(it.code, it.header("Location"), bytes.toString("UTF-8"), bytes.size())
                        if (continuation.isActive) continuation.resume(value)
                    }
                } catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(IOException("Harita yanıtı sınırı aşıldı veya okunamadı.")) }
                finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        })
    }
}
