package org.iz.navigation.integration.osm

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.iz.navigation.integration.PreparedOsmGpxTrace

internal sealed interface OsmGpxUploadResult {
    data class Received(val traceId: Long) : OsmGpxUploadResult
    data class Rejected(val statusCode: Int, val retryAfterMillis: Long? = null) : OsmGpxUploadResult
    data object Unknown : OsmGpxUploadResult
    data object NotDispatched : OsmGpxUploadResult
}

enum class OsmGpxVisibility(internal val wireValue: String) {
    IDENTIFIABLE("identifiable"),
    TRACKABLE("trackable"),
}

internal fun interface OsmGpxGateway {
    suspend fun upload(
        token: String,
        trace: PreparedOsmGpxTrace,
        visibility: OsmGpxVisibility,
        canDispatch: () -> Boolean,
    ): OsmGpxUploadResult
}

internal class OsmGpxClient(baseUrl: String = "https://api.openstreetmap.org/api/0.6") : OsmGpxGateway {
    private val url = baseUrl.toHttpUrl().also { base ->
        val official = base.isHttps && base.host == "api.openstreetmap.org" && base.port == 443
        val local = base.scheme == "http" && base.host in setOf("localhost", "127.0.0.1", "::1")
        require((official || local) && base.username.isEmpty() && base.password.isEmpty() &&
            base.query == null && base.fragment == null && base.encodedPath == "/api/0.6")
    }.newBuilder().addPathSegment("gpx").build()

    private class Attempt(val canDispatch: () -> Boolean) {
        val started = AtomicBoolean()
        @Volatile var blocked = false
    }

    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val attempt = checkNotNull(chain.request().tag(Attempt::class.java))
            // Match the community client's exchange guard, including the 503 / Retry-After: 0 path.
            if (!attempt.started.compareAndSet(false, true)) throw IOException("Repeated GPX exchange")
            if (!attempt.canDispatch()) {
                attempt.blocked = true
                throw IOException("GPX dispatch no longer allowed")
            }
            chain.proceed(chain.request())
        }.build()

    override suspend fun upload(
        token: String,
        trace: PreparedOsmGpxTrace,
        visibility: OsmGpxVisibility,
        canDispatch: () -> Boolean,
    ): OsmGpxUploadResult {
        if (!canDispatch()) return OsmGpxUploadResult.NotDispatched
        require(token.isNotBlank() && token.all { it in '!'..'~' })
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", trace.fileName, trace.gpxBytes.toRequestBody("application/gpx+xml".toMediaType()))
            .addFormDataPart("description", "Iz GPS trace")
            .addFormDataPart("tags", "")
            .addFormDataPart("visibility", visibility.wireValue)
            .build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Iz/Android (OpenStreetMap GPX)")
            .tag(Attempt::class.java, Attempt(canDispatch))
            .post(object : RequestBody() {
                override fun contentType() = multipart.contentType()
                override fun contentLength() = multipart.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = multipart.writeTo(sink)
            }).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val blocked = request.tag(Attempt::class.java)?.blocked == true
                    val result = if (blocked) OsmGpxUploadResult.NotDispatched else OsmGpxUploadResult.Unknown
                    if (continuation.isActive) continuation.resume(result)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        try {
                            readResult(it)
                        } catch (_: IOException) {
                            OsmGpxUploadResult.Unknown
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    private fun readResult(response: Response): OsmGpxUploadResult {
        if (response.isSuccessful) {
            val source = response.body?.source() ?: return OsmGpxUploadResult.Unknown
            if (source.request(129L)) return OsmGpxUploadResult.Unknown
            val id = source.readUtf8().trim().takeIf { value ->
                value.isNotEmpty() && value.all { it in '0'..'9' }
            }?.toLongOrNull()
            return if (id != null && id > 0) OsmGpxUploadResult.Received(id) else OsmGpxUploadResult.Unknown
        }
        return when (response.code) {
            429 -> OsmGpxUploadResult.Rejected(429, retryAfterMillis(response.header("Retry-After")))
            400, 401, 403, 404, 405, 413, 415, 422 -> OsmGpxUploadResult.Rejected(response.code)
            else -> OsmGpxUploadResult.Unknown
        }
    }

    private fun retryAfterMillis(value: String?): Long? {
        val header = value?.trim() ?: return null
        header.toLongOrNull()?.let { seconds ->
            if (seconds >= 0) return TimeUnit.SECONDS.toMillis(seconds)
        }
        return runCatching {
            val until = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (until.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
        }.getOrNull()
    }
}
