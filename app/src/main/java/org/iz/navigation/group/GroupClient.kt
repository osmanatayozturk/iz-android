package org.iz.navigation.group

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.BuildConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** No HTTP logger, location persistence, request retry queue, or service credential. */
internal class GroupClient(
    private val url: String,
    private val key: String,
    private val tokenFile: File,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
) : GroupBackend {
    constructor(context: Context) : this(BuildConfig.GROUP_SUPABASE_URL.trimEnd('/'), BuildConfig.GROUP_SUPABASE_KEY, File(context.noBackupFilesDir, "group-device-session.json"))
    override val configured = url.startsWith("https://") && key.isNotBlank()
    private var session: JSONObject? = runCatching { JSONObject(tokenFile.readText()) }.getOrNull()
    override val hasIdentity: Boolean get() = session != null
    private fun request(path: String, body: JSONObject, token: String? = null): JSONObject {
        val builder = Request.Builder().url("$url$path").header("apikey", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (token != null) builder.header("Authorization", "Bearer $token")
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (path.startsWith("/functions")) return GroupWire.requireSuccess(response.code, text)
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(text) }.getOrNull()
                val code = error?.optString("error_code")?.takeIf { it.isNotBlank() } ?: error?.optString("code")
                val expired = path.startsWith("/auth/v1/token") && response.code in listOf(400,401) && code in listOf("refresh_token_not_found","refresh_token_already_used","refresh_token_expired","session_not_found","user_not_found")
                throw GroupException(if (expired) "identity_expired" else if (response.code == 429) "rate_limited" else "identity_unavailable")
            }
            return runCatching { JSONObject(text) }.getOrElse { throw GroupException("invalid_response") }
        }
    }
    @Synchronized private fun loadToken(): String {
        if (!configured) throw GroupException("unconfigured")
        val old = session
        if (old != null && old.optString("refresh_token").isNotBlank() && old.optLong("expires_at") > System.currentTimeMillis()/1000 + 60) return old.getString("access_token")
        var identityChanged = old != null && old.optString("refresh_token").isBlank()
        val next = if (old?.optString("refresh_token").isNullOrBlank()) request("/auth/v1/signup", JSONObject())
        else try {
            request("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token",old!!.getString("refresh_token")))
        } catch (error: GroupException) {
            if (error.reason != "identity_expired") throw error
            // A confirmed revoked/deleted credential is unrecoverable. Never rotate on network/429/unknown errors.
            session = null
            tokenFile.delete()
            identityChanged = true
            try { request("/auth/v1/signup", JSONObject()) }
            catch (_: Exception) { throw GroupException("identity_changed") }
        }
        if (next.optString("access_token").isBlank() || next.optString("refresh_token").isBlank() || next.optJSONObject("user")?.optString("id").isNullOrBlank()) throw GroupException("invalid_response")
        if (!next.has("expires_at")) next.put("expires_at", System.currentTimeMillis()/1000 + next.getLong("expires_in"))
        session = next
        val temp = File(tokenFile.parentFile, "group-device-session.tmp")
        temp.writeText(next.toString()); if (!temp.renameTo(tokenFile)) { temp.delete(); throw GroupException("identity_storage_failed") }
        if (identityChanged || old != null && next.optJSONObject("user")?.optString("id") != old.optJSONObject("user")?.optString("id")) throw GroupException("identity_changed")
        return next.getString("access_token")
    }
    override suspend fun token(): String = withContext(Dispatchers.IO) { loadToken() }
    override suspend fun command(action: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) { request("/functions/v1/group-api", body.put("action", action), loadToken()) }
    override fun openSocket(listener: WebSocketListener): WebSocket = http.newWebSocket(Request.Builder().url(socketUrl()).build(), listener)
    fun socketUrl(): String = url.replaceFirst("https://", "wss://") + "/realtime/v1/websocket?apikey=$key&vsn=1.0.0"
}



