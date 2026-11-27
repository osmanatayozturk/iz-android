package org.iz.navigation.integration.osm

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.iz.navigation.offline.OfflineJsonDepth
import org.json.JSONObject

internal fun interface OsmGpxHistoryGateway {
    suspend fun list(token: String, expectedUserId: Long): List<OsmGpxHistoryEntry>
    suspend fun data(token: String, id: Long): OsmGpxRemoteData = throw IOException("GPX data unavailable")
    suspend fun readMetadata(token: String, expectedUserId: Long, id: Long,
        canDispatch: () -> Boolean): OsmGpxRemoteMetadata = throw IOException("GPX metadata unavailable")
    suspend fun changeToTrackable(token: String, expectedUserId: Long, id: Long,
        canDispatch: () -> Boolean, beforePut: (OsmGpxRemoteMetadata) -> Boolean = { true }): Boolean = false
}

internal class OsmGpxHistoryHttpException(val statusCode: Int) : IOException("OpenStreetMap HTTP $statusCode")
internal class OsmGpxMetadataFormatException : IOException("Invalid GPX history metadata")

internal class OsmGpxVisibilityIneligibleException : IOException("GPX visibility change is no longer eligible")

internal class OsmGpxVisibilityDeniedException(val statusCode: Int) : IOException("OpenStreetMap GPX visibility HTTP $statusCode")

internal class OsmGpxHistoryClient(baseUrl: String = "https://api.openstreetmap.org/api/0.6") : OsmGpxHistoryGateway {
    private val base = baseUrl.toHttpUrl().also {
        val official = it.isHttps && it.host == "api.openstreetmap.org" && it.port == 443
        val local = it.scheme == "http" && it.host in setOf("localhost", "127.0.0.1", "::1")
        require((official || local) && it.username.isEmpty() && it.password.isEmpty() &&
            it.query == null && it.fragment == null && it.encodedPath == "/api/0.6")
    }
    private class Exchange(val canDispatch: () -> Boolean) { val started = AtomicBoolean() }
    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .callTimeout(30, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val exchange = checkNotNull(chain.request().tag(Exchange::class.java))
            if (!exchange.started.compareAndSet(false, true)) throw IOException("Repeated GPX exchange")
            if (!exchange.canDispatch()) throw IOException("GPX dispatch no longer allowed")
            val response = chain.proceed(chain.request())
            if (response.code == 503 && response.header("Retry-After")?.toLongOrNull() == 0L)
                response.newBuilder().header("Retry-After", "1").build() else response
        }.build()

    override suspend fun list(token: String, expectedUserId: Long): List<OsmGpxHistoryEntry> {
        require(expectedUserId > 0)
        return execute(request(token, "user/gpx_files.json")) { response ->
            metadataJson(response, 4L * 1024 * 1024) { root ->
                val rows = root.getJSONArray("traces")
                requireOsmGpxDataLimit(rows.length() <= MAX_HISTORY_ROWS)
                val ids = HashSet<Long>()
                List(rows.length()) { index ->
                    val entry = OsmGpxRemoteMetadata.parseHistoryEntry(rows.getJSONObject(index), expectedUserId)
                    require(ids.add(entry.id))
                    entry
                }
            }
        }
    }

    override suspend fun data(token: String, id: Long): OsmGpxRemoteData {
        require(id > 0)
        // Decode explicitly so compressed and expanded bytes each have a bound.
        val get = request(token, "gpx/$id/data.gpx", "application/gpx+xml").newBuilder()
            .header("Accept-Encoding", "identity").build()
        return execute(get, beforeResponseError = { classifyGpxTransfer(it, readingBody = false) }) { response ->
            requireSuccess(response)
            val encoding = response.header("Content-Encoding")
            if (encoding != null && !encoding.equals("identity", true) && !encoding.equals("gzip", true))
                throw OsmGpxGeometryFormatException()
            val bytes = try {
                readLimited(response, OsmGpxRemoteXml.MAX_BYTES)
            } catch (limit: OsmGpxDataLimitException) {
                throw limit
            } catch (error: IOException) {
                throw classifyGpxTransfer(error, readingBody = true)
            }
            val length = response.body?.contentLength() ?: -1L
            if (length >= 0 && length != bytes.size.toLong()) throw OsmGpxTransferException(OsmGpxTransferReason.BODY_LENGTH)
            if (encoding.equals("gzip", true) && !OsmGpxRemoteXml.isGzip(bytes)) throw OsmGpxGeometryFormatException()
            OsmGpxRemoteXml.parse(bytes, response.body?.contentType()?.toString())
        }
    }

    override suspend fun changeToTrackable(token: String, expectedUserId: Long, id: Long,
        canDispatch: () -> Boolean, beforePut: (OsmGpxRemoteMetadata) -> Boolean): Boolean {
        require(expectedUserId > 0 && id > 0)
        if (!canDispatch()) return false
        val before = readMetadata(token, expectedUserId, id, canDispatch)
        if (!canDispatch()) return false
        if (before.pending || before.visibility !in setOf("identifiable", "trackable"))
            throw OsmGpxVisibilityIneligibleException()
        if (before.visibility == "trackable") return true
        val payload = before.trackableXml().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val put = request(token, "gpx/$id", "application/xml", canDispatch).newBuilder()
            .put(object : RequestBody() {
                override fun contentType() = payload.contentType()
                override fun contentLength() = payload.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = payload.writeTo(sink)
            }).build()
        if (!canDispatch() || !beforePut(before)) return false
        // After dispatch, a lost reply or failed verification never permits an automatic replay.
        return try {
            val status = execute(put) { it.code }
            if (status == 401 || status == 403) throw OsmGpxVisibilityDeniedException(status)
            if (status != 200 || !canDispatch()) false
            else {
                val after = readMetadata(token, expectedUserId, id, canDispatch)
                canDispatch() && !after.pending && after.visibility == "trackable" && before.samePreservedFields(after)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (denied: OsmGpxVisibilityDeniedException) { throw denied }
        catch (_: IOException) { false }
    }

    override suspend fun readMetadata(token: String, expectedUserId: Long, id: Long,
        canDispatch: () -> Boolean): OsmGpxRemoteMetadata {
        require(expectedUserId > 0 && id > 0)
        if (!canDispatch()) throw IOException("GPX metadata authorization changed")
        val result = execute(request(token, "gpx/$id.json", canDispatch = canDispatch)) { response ->
            metadataJson(response, 256L * 1024) { root ->
                OsmGpxRemoteMetadata.parse(root.getJSONObject("trace"), expectedUserId).also { require(it.id == id) }
            }
        }
        if (!canDispatch()) throw IOException("GPX metadata authorization changed")
        return result
    }

    private fun request(token: String, path: String, accept: String = "application/json",
        canDispatch: () -> Boolean = { true }): Request {
        require(token.isNotBlank() && token.all { it in '!'..'~' })
        return Request.Builder().url(base.newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer $token").header("Accept", accept)
            .header("User-Agent", "Iz/Android (OpenStreetMap GPX)")
            .tag(Exchange::class.java, Exchange(canDispatch)).get().build()
    }

    private suspend fun <T> execute(request: Request, beforeResponseError: (IOException) -> IOException = { it },
                                    read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(beforeResponseError(e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (continuation.isActive) {
                            val result = read(it)
                            if (continuation.isActive) continuation.resume(result)
                        }
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (error is IOException) error else IOException("Invalid GPX response"))
                }
            }
        })
    }

    private fun <T> metadataJson(response: Response, limit: Long, parse: (JSONObject) -> T): T {
        requireSuccess(response)
        val bytes = readLimited(response, limit)
        try {
            val json = OsmGpxRemoteXml.utf8(bytes)
            val root = JSONObject(OfflineJsonDepth.requireSafe(json))
            require(!root.has("error"))
            return parse(root)
        } catch (limit: OsmGpxDataLimitException) { throw limit }
        catch (_: Exception) { throw OsmGpxMetadataFormatException() }
    }
    private fun requireSuccess(response: Response) {
        if (response.code != 200) throw OsmGpxHistoryHttpException(response.code)
    }
    private fun readLimited(response: Response, limit: Long): ByteArray {
        val body = response.body ?: throw IOException("Missing GPX response")
        val source = body.source()
        requireOsmGpxDataLimit(body.contentLength() <= limit && !source.request(limit + 1))
        return source.readByteArray()
    }

    private companion object { const val MAX_HISTORY_ROWS = 10_000 }
}
