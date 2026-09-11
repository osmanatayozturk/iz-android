package org.iz.navigation.integration.osm

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.iz.navigation.BuildConfig
import java.util.Base64
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OsmAuthUiState(val user: OsmUser? = null, val busy: Boolean = false,
    val error: String? = null, val clientIdConfigured: Boolean = false, val canWriteMap: Boolean = false)

class OsmAuthManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val storage = OsmAuthStorage(app)
    private val service by lazy { AuthorizationService(app) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = OsmNotesClient()
    private var clientId = BuildConfig.OSM_CLIENT_ID.trim()
    private var pendingRequest: String? = null
    private var pendingStartedAt = 0L
    private var generation = 0L
    @Volatile private var credentials: OsmCredentials? = null
    private val mutableState = MutableStateFlow(OsmAuthUiState())
    val state: StateFlow<OsmAuthUiState> = mutableState.asStateFlow()
    private val communitySource: OsmCommunityAuthSource = OsmCommunityAuthSource { expected ->
        scope.launch {
            // The account can change while a background worker's 401 reaches Main.
            if (communitySourceIsCurrent(expected)) {
                disconnect()
                publish(error = "OSM oturumu geçersiz. Hesabınızı yeniden bağlayın.")
            }
        }
    }
    internal val communitySessionSource: org.iz.navigation.osmcommunity.OsmCommunitySessionSource
        get() = communitySource
    private fun communitySourceIsCurrent(expected: org.iz.navigation.osmcommunity.CommunitySession): Boolean =
        communitySource.isCurrent(expected)

    init {
        try {
            storage.read()?.let { saved ->
                clientId = saved.optString("clientId", clientId)
                pendingRequest = saved.optString("pendingRequest").takeIf { it.isNotBlank() }
                pendingStartedAt = saved.optLong("pendingStartedAt")
                saved.optJSONObject("session")?.let { session ->
                    val user = OsmUser(session.getLong("userId"), session.getString("displayName"))
                    val token = session.getString("accessToken")
                    val expires = session.optLong("expiresAt").takeIf { it > 0 }
                    if (user.id > 0 && user.displayName.isNotBlank() && token.isNotBlank() &&
                        (expires == null || expires > System.currentTimeMillis())) {
                        val scopes = session.optJSONArray("scopes")?.let { values ->
                            (0 until values.length()).map { values.getString(it) }.toSet()
                        } ?: setOf("read_prefs", "write_notes")
                        credentials = OsmCredentials(token, user, expires, scopes)
                    }
                }
            }
            publish()
        } catch (_: Exception) {
            credentials = null
            pendingRequest = null
            storage.clear()
            publish(error = "OSM oturumu açılamadı. Hesabınızı yeniden bağlayın.")
        }
    }

    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }

    /** Public client ID only; no client secret is used by this native app. */
    fun setClientId(value: String) {
        val clean = value.trim()
        if (clean.isNotEmpty() && !clean.matches(Regex("[A-Za-z0-9_-]{10,256}"))) {
            publish(error = "Geçerli OSM uygulama kimliğini girin.")
            return
        }
        generation++
        credentials = null
        pendingRequest = null
        clientId = clean
        saveOrDisconnect()
    }

    fun disconnect() {
        generation++
        credentials = null
        pendingRequest = null
        saveOrDisconnect()
    }

    internal fun currentCredentials(): OsmCredentials? = credentials?.takeIf {
        !state.value.busy && (it.expiresAt == null || it.expiresAt > System.currentTimeMillis())
    }

    internal fun rejectExpiredSession(expected: OsmCredentials) {
        scope.launch {
            if (credentials === expected && !state.value.busy) {
                disconnect()
                publish(error = "OSM oturumu geçersiz. Hesabınızı yeniden bağlayın.")
            }
        }
    }

    fun startMapEditLogin(activity: Activity) = startLogin(activity, allowMapEdits = true)
    fun startCommunityLogin(activity: Activity) = beginLogin(activity, community = true)

    fun startLogin(activity: Activity, allowMapEdits: Boolean = false) =
        beginLogin(activity, mapEdits = allowMapEdits)

    private fun beginLogin(activity: Activity, mapEdits: Boolean = false, community: Boolean = false) {
        if (state.value.busy) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (clientId.isBlank()) {
            publish(error = "OSM uygulama kimliği yapılandırılmamış. Ayarlara kayıtlı OAuth uygulama kimliğini girin.")
            return
        }
        try {
            generation++
            val request = AuthorizationRequest.Builder(configuration(), clientId, ResponseTypeValues.CODE, Uri.parse(OsmSafetyRules.REDIRECT_URI))
                .setScopes(OsmOAuthScopes.requested(credentials?.scopes.orEmpty(), mapEdits, community))
                .setState(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }))
                .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
                .build()
            pendingStartedAt = System.currentTimeMillis()
            pendingRequest = request.jsonSerializeString()
            persist()
            publish(busy = true)
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
            fun resultIntent(action: String) = Intent(app, OsmOAuthCallbackActivity::class.java)
                .setAction(action).addCategory("org.iz.navigation.OSM_TRANSACTION.${request.state}")
                .putExtra(EXTRA_TRANSACTION_STATE, request.state)
            val complete = PendingIntent.getActivity(app, 701,
                resultIntent(ACTION_COMPLETE), flags)
            val cancel = PendingIntent.getActivity(app, 702,
                resultIntent(ACTION_CANCEL), flags)
            service.performAuthorizationRequest(request, complete, cancel)
        } catch (_: Exception) {
            pendingRequest = null
            runCatching { persist() }
            publish(error = "OSM giriş sayfası açılamadı. Bir tarayıcı kurulu olduğundan emin olun.")
        }
    }

    internal fun handleAuthorizationResult(intent: Intent) {
        val serialized = pendingRequest ?: return
        val request = runCatching { AuthorizationRequest.jsonDeserialize(serialized) }.getOrNull() ?: return
        if (intent.action !in setOf(ACTION_COMPLETE, ACTION_CANCEL) ||
            intent.getStringExtra(EXTRA_TRANSACTION_STATE) != request.state) return
        val started = pendingStartedAt
        pendingRequest = null
        val attemptGeneration = generation
        // Consume the transaction durably before exchanging its single-use code.
        if (runCatching { persist() }.isFailure) {
            credentials = null
            storage.clear()
            publish(error = "OSM oturumu güvenli olarak saklanamadı.")
            return
        }
        if (intent.action == ACTION_CANCEL) {
            publish(error = "OSM hesap bağlantısı iptal edildi.")
            return
        }
        scope.launch {
            publish(busy = true)
            val previousCredentials = credentials
            try {
                // AppAuth forwards the original callback URI in Intent.data. Validate its path too.
                require(OsmSafetyRules.isExactRedirect(intent.dataString))
                val response = AuthorizationResponse.fromIntent(intent)
                    ?: throw (AuthorizationException.fromIntent(intent) ?: IllegalStateException("Missing OAuth response"))
                require(OsmSafetyRules.validCallback(OAuthTransaction(request.clientId, request.state.orEmpty(), started),
                    response.request.clientId, response.state, response.request.redirectUri.toString(), System.currentTimeMillis()))
                require(request.clientId == clientId && response.request.state == request.state)
                require(!request.codeVerifier.isNullOrBlank() && request.codeVerifierChallengeMethod == "S256")
                require(request.configuration.authorizationEndpoint == configuration().authorizationEndpoint &&
                    request.configuration.tokenEndpoint == configuration().tokenEndpoint &&
                    response.request.configuration.authorizationEndpoint == configuration().authorizationEndpoint &&
                    response.request.configuration.tokenEndpoint == configuration().tokenEndpoint)
                val tokenRequest = TokenRequest.Builder(configuration(), clientId)
                    .setGrantType(GrantTypeValues.AUTHORIZATION_CODE).setAuthorizationCode(response.authorizationCode)
                    .setRedirectUri(Uri.parse(OsmSafetyRules.REDIRECT_URI)).setCodeVerifier(request.codeVerifier).build()
                val tokenResponse = exchange(tokenRequest)
                val token = tokenResponse.accessToken?.takeIf { it.isNotBlank() } ?: error("Missing access token")
                val granted = (tokenResponse.scope ?: request.scope.orEmpty()).split(" ").toSet()
                require(granted.containsAll(request.scope.orEmpty().split(" ").filter(String::isNotBlank)))
                val user = api.currentUser(token)
                if (attemptGeneration != generation) return@launch
                credentials = OsmCredentials(token, user, tokenResponse.accessTokenExpirationTime, granted)
                persist()
                publish()
            } catch (_: Exception) {
                if (attemptGeneration == generation) {
                    // A denied scope upgrade must leave the previous Notes session usable.
                    credentials = previousCredentials
                    runCatching { persist() }
                    publish(error = "OSM izni alınamadı. Girişi yeniden başlatıp istenen izinleri onaylayın. Mevcut hesap bağlantınız korundu.")
                }
            }
        }
    }

    private suspend fun exchange(request: TokenRequest): TokenResponse = suspendCancellableCoroutine { continuation ->
        service.performTokenRequest(request, NoClientAuthentication.INSTANCE) { response, error ->
            if (continuation.isActive) {
                if (response != null) continuation.resume(response)
                else continuation.resumeWithException(error ?: IllegalStateException("Token exchange failed"))
            }
        }
    }

    private fun saveOrDisconnect() {
        try { persist(); publish() }
        catch (_: Exception) { credentials = null; storage.clear(); publish(error = "OSM oturumu güvenli olarak saklanamadı.") }
    }

    private fun persist() {
        val value = JSONObject().put("clientId", clientId).put("pendingStartedAt", pendingStartedAt)
        pendingRequest?.let { value.put("pendingRequest", it) }
        credentials?.let {
            value.put("session", JSONObject().put("accessToken", it.token).put("userId", it.user.id)
                .put("displayName", it.user.displayName).put("expiresAt", it.expiresAt)
                .put("scopes", org.json.JSONArray(it.scopes.sorted())))
        }
        storage.write(value)
    }

    private fun publish(busy: Boolean = false, error: String? = null) {
        communitySource.update(credentials, generation)
        mutableState.value = OsmAuthUiState(credentials?.user, busy, error, clientId.isNotBlank(),
            credentials?.scopes?.contains("write_api") == true)
    }

    companion object {
        internal const val ACTION_COMPLETE = "org.iz.navigation.OSM_AUTH_COMPLETE"
        internal const val ACTION_CANCEL = "org.iz.navigation.OSM_AUTH_CANCEL"
        private const val EXTRA_TRANSACTION_STATE = "org.iz.navigation.OSM_TRANSACTION_STATE"
        private fun configuration() = AuthorizationServiceConfiguration(
            Uri.parse("https://www.openstreetmap.org/oauth2/authorize"),
            Uri.parse("https://www.openstreetmap.org/oauth2/token"))
        @Volatile private var instance: OsmAuthManager? = null
        fun get(context: Context): OsmAuthManager = instance ?: synchronized(this) {
            instance ?: OsmAuthManager(context).also { instance = it }
        }
    }
}
