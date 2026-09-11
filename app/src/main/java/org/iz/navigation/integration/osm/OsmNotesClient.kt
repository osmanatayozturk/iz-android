package org.iz.navigation.integration.osm

import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject

class OsmApiException(val statusCode: Int) : IOException("OpenStreetMap HTTP $statusCode")
internal interface OsmNotesGateway {
    suspend fun createNote(token: String, latitude: Double, longitude: Double, text: String): OsmRemoteNote
    suspend fun readNote(id: Long): OsmRemoteNote
    suspend fun nearbyNotes(latitude: Double, longitude: Double): List<OsmRemoteNote>
}

class OsmNotesClient(client: OkHttpClient = OkHttpClient()) : OsmNotesGateway {
    // POST cannot be safely replayed after a lost response, even to the same host.
    private val http = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()

    override suspend fun createNote(token: String, latitude: Double, longitude: Double, text: String): OsmRemoteNote =
        withContext(Dispatchers.IO) {
            require(token.isNotBlank() && validCoordinate(latitude, longitude) && text.isNotBlank() && text.length <= 2000)
            val payload = JSONObject().put("lat", latitude).put("lon", longitude).put("text", text)
            val body = payload.toString().toRequestBody(JSON)
            // retryOnConnectionFailure(false) alone still allows a 503 + Retry-After: 0 follow-up.
            // One-shot bodies also stop server-directed retries before a second network write.
            val singleUseBody = object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
            }
            parseNote(request("notes.json", token).post(singleUseBody).build())
        }

    suspend fun currentUser(token: String): OsmUser = withContext(Dispatchers.IO) {
        val user = execute(request("user/details.json", token).build()).getJSONObject("user")
        OsmUser(user.getLong("id"), user.getString("display_name")).also {
            require(it.id > 0 && it.displayName.isNotBlank())
        }
    }

    override suspend fun readNote(id: Long): OsmRemoteNote = withContext(Dispatchers.IO) {
        require(id > 0)
        parseNote(request("notes/$id.json").build())
    }

    override suspend fun nearbyNotes(latitude: Double, longitude: Double): List<OsmRemoteNote> = withContext(Dispatchers.IO) {
        require(validCoordinate(latitude, longitude))
        val bottom = (latitude - 0.001).coerceAtLeast(-90.0)
        val top = (latitude + 0.001).coerceAtMost(90.0)
        val left = longitude - 0.001
        val right = longitude + 0.001
        val spans = when {
            left < -180 -> listOf(-180.0 to right, (left + 360) to 180.0)
            right > 180 -> listOf(left to 180.0, -180.0 to (right - 360))
            else -> listOf(left to right)
        }
        spans.flatMap { (west, east) ->
            val url = request("notes.json").build().url.newBuilder()
                .addQueryParameter("bbox", "$west,$bottom,$east,$top")
                .addQueryParameter("closed", "-1").addQueryParameter("limit", "1000").build()
            val features = execute(request("notes.json").url(url).build()).getJSONArray("features")
            // A truncated search cannot prove that a candidate is unique.
            if (features.length() >= 1000) throw IOException("OSM note search was truncated")
            (0 until features.length()).map { parseNote(features.getJSONObject(it)) }
        }.distinctBy { it.id }
    }

    private fun request(path: String, token: String? = null): Request.Builder = Request.Builder()
        .url("$API_BASE/$path").header("Accept", "application/json")
        .header("User-Agent", "Iz-Android/0.1 (org.iz.navigation)")
        .apply { if (token != null) header("Authorization", "Bearer $token") }

    private fun parseNote(request: Request): OsmRemoteNote = parseNote(execute(request))

    private fun execute(request: Request): JSONObject {
        check(request.url.scheme == "https" && request.url.host == "api.openstreetmap.org")
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw OsmApiException(response.code)
            val source = response.body?.source() ?: throw IOException("OSM response was empty")
            source.request(MAX_RESPONSE_BYTES + 1)
            if (source.buffer.size > MAX_RESPONSE_BYTES) throw IOException("OSM response was too large")
            JSONObject(source.readUtf8())
        }
    }

    private fun parseNote(value: JSONObject): OsmRemoteNote {
        val geometry = value.getJSONObject("geometry")
        require(value.getString("type") == "Feature" && geometry.getString("type") == "Point")
        val coordinates = geometry.getJSONArray("coordinates")
        val properties = value.getJSONObject("properties")
        val comments = properties.getJSONArray("comments")
        return OsmRemoteNote(
            id = properties.getLong("id"), latitude = coordinates.getDouble(1), longitude = coordinates.getDouble(0),
            status = properties.getString("status"), createdAt = date(properties.getString("date_created")),
            comments = (0 until comments.length()).map { index ->
                val comment = comments.getJSONObject(index)
                OsmNoteComment(if (comment.has("uid") && !comment.isNull("uid")) comment.getLong("uid") else null,
                    comment.getString("action"), comment.getString("text"), date(comment.getString("date")))
            },
        ).also {
            require(it.id > 0 && validCoordinate(it.latitude, it.longitude) && it.status in setOf("open", "closed", "hidden"))
        }
    }

    private fun date(value: String): Long = Instant.parse(value.replace(" UTC", "Z").replace(' ', 'T')).toEpochMilli()
    private fun validCoordinate(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    companion object {
        const val API_BASE = "https://api.openstreetmap.org/api/0.6"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
    }
}
