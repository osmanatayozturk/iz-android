package org.iz.navigation.integration.osm

import java.io.IOException
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.iz.navigation.offline.OfflineJsonDepth
import org.json.JSONObject

internal fun interface OsmGpxHistoryGateway {
    suspend fun list(token: String, expectedUserId: Long): List<OsmGpxHistoryEntry>
}

internal class OsmGpxHistoryHttpException(val statusCode: Int) : IOException("OpenStreetMap HTTP $statusCode")

internal class OsmGpxHistoryClient(
    baseUrl: String = "https://api.openstreetmap.org/api/0.6",
) : OsmGpxHistoryGateway {
    private val url = baseUrl.toHttpUrl().also { base ->
        val official = base.isHttps && base.host == "api.openstreetmap.org" && base.port == 443
        val local = base.scheme == "http" && base.host in setOf("localhost", "127.0.0.1", "::1")
        require((official || local) && base.username.isEmpty() && base.password.isEmpty() &&
            base.query == null && base.fragment == null && base.encodedPath == "/api/0.6")
    }.newBuilder().addPathSegments("user/gpx_files.json").build()

    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val exchanged = checkNotNull(request.tag(AtomicBoolean::class.java))
            if (!exchanged.compareAndSet(false, true)) throw IOException("Repeated GPX history exchange")
            val response = chain.proceed(request)
            // OkHttp may otherwise replay a 503 with Retry-After: 0.
            if (response.code == 503 && response.header("Retry-After")?.toLongOrNull() == 0L) {
                response.newBuilder().header("Retry-After", "1").build()
            } else response
        }.build()

    override suspend fun list(token: String, expectedUserId: Long): List<OsmGpxHistoryEntry> {
        require(expectedUserId > 0)
        require(token.isNotBlank() && token.all { it in '!'..'~' })
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", "Iz/Android (OpenStreetMap GPX)")
            .tag(AtomicBoolean::class.java, AtomicBoolean())
            .get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!continuation.isActive) return
                            readHistory(it, expectedUserId)
                        }
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                        return
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    private fun readHistory(response: Response, expectedUserId: Long): List<OsmGpxHistoryEntry> {
        if (response.code != 200) throw OsmGpxHistoryHttpException(response.code)
        val body = response.body ?: throw IOException("Missing GPX history response")
        val source = body.source()
        if (body.contentLength() > MAX_RESPONSE_BYTES || source.request(MAX_RESPONSE_BYTES + 1L)) {
            throw IOException("GPX history response exceeds size limit")
        }
        val bytes = source.readByteArray()
        try {
            // The OSM index serializes all of the owner's traces in one response.
            val json = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            val traces = JSONObject(OfflineJsonDepth.requireSafe(json)).getJSONArray("traces")
            val ids = HashSet<Long>()
            return List(traces.length()) { index ->
                val row = traces.getJSONObject(index)
                require(row.integral("uid") == expectedUserId)
                val id = row.integral("id")
                require(id > 0 && ids.add(id))
                val name = row.get("name") as String
                require(name.isNotBlank() && name.length <= 1024 && name.none(Char::isISOControl))
                val timestamp = OffsetDateTime.parse(row.get("timestamp") as String).toInstant().toEpochMilli()
                OsmGpxHistoryEntry(id, name, timestamp, row.get("pending") as Boolean)
            }
        } catch (_: Exception) {
            throw IOException("Invalid GPX history metadata")
        }
    }

    private fun JSONObject.integral(key: String): Long = when (val value = get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("Invalid GPX metadata integer")
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
    }
}
