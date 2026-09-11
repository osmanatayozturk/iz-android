package org.iz.navigation.osmcommunity

import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject

internal class OsmCommunityClient(
    client: OkHttpClient = OkHttpClient(),
    baseUrl: String = API_BASE,
) : OsmCommunityGateway {
    private val base = checkedBase(baseUrl)
    private val http = client.newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cache(null)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val attempt = checkNotNull(chain.request().tag(Attempt::class.java))
            // OkHttp may follow 503 + Retry-After: 0 even with connection retries disabled.
            // Writes also have one-shot bodies; this guard leaves GET retry timing to the repository.
            if (!attempt.started.compareAndSet(false, true)) throw attempt.failure ?: IOException(NETWORK_ERROR)
            chain.proceed(chain.request()).also { response ->
                if (!response.isSuccessful) attempt.failure = httpError(response)
            }
        }.build()

    override suspend fun profile(token: String?, userId: Long?): OsmCommunityProfile {
        require(userId == null || userId > 0) { "OSM kullanıcı kimliği geçersiz." }
        require(userId != null || token != null) { "OSM oturumu gerekli." }
        val path = if (userId == null) "user/details.json" else "user/$userId.json"
        val data = execute(request(path, token).build())
        return parse(data) { root ->
            val user = root.getJSONObject("user")
            val id = positiveId(user, "id")
            if (userId != null) require(id == userId)
            OsmCommunityProfile(
                id = id, displayName = nonblank(user, "display_name"),
                description = user.optString("description", ""),
                imageUrl = CommunityLinks.safeImageUrl(user.optJSONObject("img")?.optString("href")),
                accountCreatedAt = if (user.has("account_created") && !user.isNull("account_created")) date(user.getString("account_created")) else null,
                changesetCount = (user.optJSONObject("changesets")?.optInt("count") ?: 0).coerceAtLeast(0),
                unreadCount = (user.optJSONObject("messages")?.optJSONObject("received")?.optInt("unread") ?: 0).coerceAtLeast(0),
            )
        }
    }

    override suspend fun mailbox(token: String, box: CommunityMailbox, fromId: Long?): CommunityPage {
        require(fromId == null || fromId > 0) { "OSM mesaj sayfası geçersiz." }
        val path = "user/messages/${if (box == CommunityMailbox.INBOX) "inbox" else "outbox"}.json"
        val builder = request(path, token)
        val url = builder.build().url.newBuilder().addQueryParameter("order", "newest")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .apply { if (fromId != null) addQueryParameter("from_id", fromId.toString()) }.build()
        val data = execute(builder.url(url).build())
        return parse(data) { root ->
            val rows = root.getJSONArray("messages")
            require(rows.length() <= PAGE_SIZE)
            val messages = (0 until rows.length()).map { summary(rows.getJSONObject(it)) }
            require(fromId == null || messages.all { it.id <= fromId })
            val minimum = messages.minOfOrNull { it.id }
            // Deleted records count towards the server's limit and must advance the cursor.
            CommunityPage(messages, minimum?.takeIf { rows.length() == PAGE_SIZE && it > 1L }?.minus(1L))
        }
    }

    override suspend fun message(token: String, id: Long): OsmMessageDetail {
        require(id > 0) { "OSM mesaj kimliği geçersiz." }
        val data = execute(request("user/messages/$id.json", token).build())
        return parse(data) { root ->
            val message = root.getJSONObject("message")
            val summary = summary(message)
            require(summary.id == id)
            OsmMessageDetail(summary, message.getString("body"))
        }
    }

    override suspend fun send(token: String, recipientId: Long?, recipientName: String, title: String, body: String): OsmMessageDetail {
        require(recipientId == null || recipientId > 0) { "OSM alıcı kimliği geçersiz." }
        require(recipientId != null || recipientName.isNotBlank()) { "Alıcı gerekli." }
        require(title.isNotBlank() && CommunityText.titleLength(title) <= 255) { "Konu 1-255 karakter olmalı." }
        require(body.isNotBlank()) { "Mesaj metni gerekli." }
        val payload = JSONObject().put("title", title).put("body", body).apply {
            if (recipientId != null) put("recipient_id", recipientId) else put("recipient", recipientName)
        }
        val data = execute(request("user/messages.json", token).post(oneShot(payload.toString())).build())
        return parse(data) { root ->
            // The accepted summary identifies the send; keep the author's exact raw text.
            val accepted = summary(root.getJSONObject("message"))
            require(recipientId == null || accepted.toId == recipientId)
            OsmMessageDetail(accepted, body)
        }
    }

    override suspend fun markRead(token: String, id: Long, read: Boolean) {
        require(id > 0) { "OSM mesaj kimliği geçersiz." }
        execute(request("user/messages/$id.json", token)
            .put(oneShot(JSONObject().put("read_status", read.toString()).toString())).build())
    }

    override suspend fun delete(token: String, id: Long) {
        require(id > 0) { "OSM mesaj kimliği geçersiz." }
        execute(request("user/messages/$id.json", token).delete(oneShot("")).build())
    }

    private fun request(path: String, token: String?): Request.Builder = Request.Builder()
        .url(base.newBuilder().addPathSegments(path).build())
        .header("Accept", "application/json").header("User-Agent", "Iz-Android/0.7.0 (org.iz.navigation)")
        .tag(Attempt::class.java, Attempt()).apply {
            if (token != null) {
                require(token.isNotBlank() && token.all { it in '\u0021'..'\u007e' }) { "OSM oturumu geçersiz." }
                header("Authorization", "Bearer $token")
            }
        }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    if (e is CommunityHttpException) e else IOException(NETWORK_ERROR),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!response.isSuccessful) throw httpError(response)
                        val body = response.body ?: throw IOException(RESPONSE_ERROR)
                        if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException(RESPONSE_ERROR)
                        val source = body.source()
                        source.request(MAX_RESPONSE_BYTES + 1)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) throw IOException(RESPONSE_ERROR)
                        source.readUtf8()
                    }
                }
                if (continuation.isActive) result.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWithException(if (it is CommunityHttpException) it else IOException(RESPONSE_ERROR)) },
                )
            }
        })
    }

    private fun summary(value: JSONObject): OsmMessageSummary = OsmMessageSummary(
        id = positiveId(value, "id"), fromId = positiveId(value, "from_user_id"),
        fromName = nonblank(value, "from_display_name"), toId = positiveId(value, "to_user_id"),
        toName = nonblank(value, "to_display_name"), title = value.getString("title"),
        sentAt = date(value.getString("sent_on")),
        // OSM omits message_read for senders. Only received rows may be shown as unread.
        read = if (value.has("message_read")) value.getBoolean("message_read") else true,
        deleted = value.getBoolean("deleted"),
    )

    private fun <T> parse(data: String, block: (JSONObject) -> T): T = try {
        block(JSONObject(data))
    } catch (_: Exception) {
        // JSON and date exceptions can embed private response content; do not retain their cause.
        throw IOException(RESPONSE_ERROR)
    }

    private fun positiveId(value: JSONObject, name: String): Long = value.getLong(name).also { require(it > 0) }
    private fun nonblank(value: JSONObject, name: String): String = value.getString(name).also { require(it.isNotBlank()) }
    private fun date(value: String): Long = Instant.parse(value).toEpochMilli()

    private fun oneShot(text: String): RequestBody {
        val body = text.toRequestBody(JSON)
        return object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        }
    }

    private class Attempt {
        val started = AtomicBoolean()
        @Volatile var failure: CommunityHttpException? = null
    }

    companion object {
        const val API_BASE = "https://api.openstreetmap.org/api/0.6"
        private const val PAGE_SIZE = 100
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private const val NETWORK_ERROR = "OSM bağlantısı tamamlanamadı."
        private const val RESPONSE_ERROR = "OSM yanıtı doğrulanamadı."
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun checkedBase(value: String): HttpUrl {
            val url = requireNotNull(value.toHttpUrlOrNull()) { "OSM adresi geçersiz." }
            val official = url.scheme == "https" && url.host == "api.openstreetmap.org" && url.port == 443
            val loopbackTest = url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1")
            require((official || loopbackTest) && url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null && url.encodedPath.trimEnd('/') == "/api/0.6") { "OSM adresi geçersiz." }
            return url.newBuilder().encodedPath("/api/0.6").build()
        }

        private fun httpError(response: Response): CommunityHttpException = CommunityHttpException(
            response.code, retryAfter(response.header("Retry-After")),
        )

        private fun retryAfter(value: String?): Long? {
            if (value == null) return null
            value.trim().toLongOrNull()?.let {
                return if (it >= 0) it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 else null
            }
            return try {
                (ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                    System.currentTimeMillis()).coerceAtLeast(0)
            } catch (_: Exception) { null }
        }
    }
}
