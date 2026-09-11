package com.atay.iz.weather

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

const val DEFAULT_ROUTE_ENDPOINT = "https://valhalla1.openstreetmap.de/route"
const val DEFAULT_WEATHER_ENDPOINT = "https://api.open-meteo.com/v1/forecast"

class RouteServiceException(message: String, cause: Throwable? = null) : IOException(message, cause)
class WeatherServiceException(message: String, cause: Throwable? = null) : IOException(message, cause)
internal class BoundedResponseException(message: String) : IOException(message)

internal const val WEATHER_USER_AGENT = "Iz/0.6.0 (com.atay.iz; journey weather)"

internal fun defaultWeatherHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

internal fun singleAttemptHttpClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 503 && response.header("Retry-After") == "0") {
            response.newBuilder().header("Retry-After", "1").build()
        } else response
    }
    .build()

internal fun weatherStorage(context: Context, name: String): File =
    File(context.applicationContext.cacheDir, name)

internal fun weatherRequest(url: String): Request.Builder = Request.Builder().url(url)
    .header("User-Agent", WEATHER_USER_AGENT)
    .header("Accept", "application/json")
    .header("Accept-Language", "tr")

internal data class BoundedHttpResponse(
    val code: Int,
    val successful: Boolean,
    val retryAfter: String?,
    val body: String,
)

internal suspend fun executeBounded(
    client: OkHttpClient,
    request: Request,
    maxBytes: Int,
): BoundedHttpResponse = suspendCancellableCoroutine { continuation ->
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    val result = BoundedHttpResponse(
                        code = response.code,
                        successful = response.isSuccessful,
                        retryAfter = response.header("Retry-After"),
                        body = readBoundedBody(response, maxBytes),
                    )
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}

internal fun readBoundedBody(response: Response, maxBytes: Int): String {
    require(maxBytes > 0)
    val body = response.body ?: throw BoundedResponseException("Servis boş yanıt verdi.")
    val declared = body.contentLength()
    if (declared > maxBytes) throw BoundedResponseException("Servis yanıtı çok büyük.")
    val output = ByteArrayOutputStream(minOf(maxBytes, 32_768))
    body.byteStream().use { input ->
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw BoundedResponseException("Servis yanıtı çok büyük.")
            output.write(buffer, 0, count)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}

internal object WeatherHttpGate {
    private val routeMutex = Mutex()
    private val forecastMutex = Mutex()

    suspend fun <T> route(directory: File, clock: () -> Long, block: suspend () -> T): T =
        gated(routeMutex, directory, "route-http-state.json", 1_000L, clock, block)

    suspend fun <T> forecast(directory: File, clock: () -> Long, block: suspend () -> T): T =
        gated(forecastMutex, directory, "forecast-http-state.json", 250L, clock, block)

    private suspend fun <T> gated(
        mutex: Mutex,
        directory: File,
        stateName: String,
        intervalMillis: Long,
        clock: () -> Long,
        block: suspend () -> T,
    ): T = mutex.withLock {
        directory.mkdirs()
        val stateFile = File(directory, stateName)
        val state = HttpState.read(stateFile)
        val now = clock()
        if (now < state.cooldownUntil) throw IOException("Servis beklememizi istiyor. Biraz sonra yeniden deneyin.")
        val wait = (state.lastRequest + intervalMillis - now).coerceIn(0L, intervalMillis)
        if (wait > 0L) delay(wait)
        coroutineContext.ensureActive()
        val requestTime = clock()
        state.copy(lastRequest = maxOf(requestTime, state.lastRequest + intervalMillis)).write(stateFile)
        block()
    }

    fun setCooldown(directory: File, stateName: String, now: Long, retryAfter: String?) {
        val file = File(directory, stateName)
        val state = HttpState.read(file)
        state.copy(cooldownUntil = maxOf(state.cooldownUntil, now + retryAfterMillis(retryAfter, now))).write(file)
    }
}

internal data class HttpState(
    val lastRequest: Long = 0L,
    val cooldownUntil: Long = 0L,
) {
    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("lastRequest", lastRequest).put("cooldownUntil", cooldownUntil).toString())
    }

    companion object {
        fun read(file: File): HttpState = runCatching {
            if (!file.isFile || file.length() !in 1..16_384) return@runCatching HttpState()
            val value = JSONObject(file.readText())
            HttpState(value.optLong("lastRequest"), value.optLong("cooldownUntil"))
        }.getOrDefault(HttpState())
    }
}

internal fun retryAfterMillis(retryAfter: String?, now: Long): Long {
    val value = retryAfter?.trim().orEmpty()
    val seconds = value.toLongOrNull()
    val parsed = if (seconds != null) seconds * 1_000L else runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now
    }.getOrDefault(30_000L)
    return parsed.coerceIn(30_000L, 86_400_000L)
}
