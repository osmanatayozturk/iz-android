package com.atay.iz.group

import android.content.Context
import android.net.Uri
import com.atay.iz.data.Transport
import com.atay.iz.navigation.JourneyNavigationCoordinator
import com.atay.iz.weather.RouteStop
import com.atay.iz.weather.WeatherCoordinate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject

/** Application-owned group session. Joining does not start GPS, recording, or sharing. */
class GroupCoordinator internal constructor(
    private val client: GroupBackend,
    private val navigation: GroupNavigation,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, navigation: JourneyNavigationCoordinator) : this(GroupClient(context.applicationContext), object : GroupNavigation {
        override val state = navigation.state
        override suspend fun currentLocation() = navigation.currentLocation()
        override suspend fun setSharingLocation(enabled: Boolean, expectedSessionId: String?) = navigation.setSharingLocation(enabled, expectedSessionId)
    })
    private val commands = Mutex()
    private val mutable = MutableStateFlow(GroupUiState(configured = client.configured))
    val state: StateFlow<GroupUiState> = mutable.asStateFlow()
    private var socket: WebSocket? = null
    private var socketInbox: String? = null
    private var socketToken: String? = null
    private var inbox: String? = null
    private var socketEpoch = 0L
    private var lastSentAt = 0L
    private var lastFixAt = 0L
    private var lastStatusAt = 0L
    private var lastHeartbeatAt = 0L
    private var joinDeadline = 0L
    private var joinAcknowledged = false
    private var pendingHeartbeatRef: String? = null
    private var heartbeatDeadline = 0L
    private var reconnectAt = 0L
    private var lastSocketAuthAt = 0L
    private var socketAuthJob: Job? = null
    private var ref = 1L
    private var sharingSession: String? = null
    private var intentEpoch = 0L
    private var screenVisible = false
    private var needsRestore = client.hasIdentity

    init {
        scope.launch {
            navigation.state.collect { nav ->
                mutable.update { it.copy(canShare = nav.sessionId != null && !nav.simulation) }
                if (mutable.value.sharing && (nav.sessionId != sharingSession || nav.simulation || !nav.sharingLocation)) setSharing(false)
            }
        }
        if (client.configured) scope.launch {
            while (isActive) {
                try {
                    commands.withLock {
                        val now = clock()
                        val before = mutable.value
                        if (before.groupId != null && before.expiresAt <= now) clearGroup("Grubun 24 saatlik süresi doldu.")
                        if ((mutable.value.groupId != null || screenVisible || needsRestore) && now - lastStatusAt >= 15_000) {
                            needsRestore = false
                            lastStatusAt = now
                            val pending = mutable.value.pendingExit
                            if (pending != null) confirmExit(client.command(pending, args()))
                            else applyResponse(client.command("status", args().put("keepalive", mutable.value.sharing)))
                        }
                        if (mutable.value.sharing) {

                            val nav = navigation.state.value
                            val fix = nav.fix
                            if (nav.sessionId == sharingSession && nav.sharingLocation && nav.locationActive && !nav.simulation && !nav.gpsStale && fix != null &&
                                fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0f..200f && fix.recordedAt > lastFixAt &&
                                GroupPolicy.shouldPublish(now, fix.recordedAt, fix.speedMps ?: 0f, lastSentAt)) {
                                // Reserve locally before send: a failed publish is dropped, never replayed.
                                lastSentAt = now; lastFixAt = fix.recordedAt
                                client.command("publish", args().put("latitude",fix.coordinate.latitude).put("longitude",fix.coordinate.longitude)
                                    .put("accuracy",fix.accuracyMeters.toDouble()).put("speed",(fix.speedMps ?: 0f).coerceIn(0f,150f).toDouble()).put("recorded_at",fix.recordedAt))
                            }
                        }

                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { handleFailure(e) }

                delay(1_000)
            }
        }
    }

    // No HTTP command mutex or token refresh may suspend socket health deadlines.
    init {
        if (client.configured) scope.launch {
            while (isActive) {
                val now = clock()
                if (mutable.value.sharing) {
                    if (socket != null && ((!joinAcknowledged && now >= joinDeadline) ||
                            (pendingHeartbeatRef != null && now >= heartbeatDeadline))) closeSocket()
                    val active = socket
                    if (active != null && joinAcknowledged && pendingHeartbeatRef == null && now - lastHeartbeatAt >= 20_000) {
                        val heartbeatRef = (++ref).toString()
                        pendingHeartbeatRef = heartbeatRef
                        heartbeatDeadline = now + 10_000
                        lastHeartbeatAt = now
                        if (!active.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat")
                                .put("payload", JSONObject()).put("ref", heartbeatRef).toString())) closeSocket()
                    }
                    if (now >= reconnectAt && socketAuthJob?.isActive != true && (socket == null || now - lastSocketAuthAt >= 15_000)) {
                        lastSocketAuthAt = now
                        socketAuthJob = scope.launch {
                            try { ensureSocket() }
                            catch (error: CancellationException) { throw error }
                            catch (error: Exception) { closeSocket(); handleFailure(error) }
                        }
                    }
                }
                mutable.update { it.copy(markers = it.markers.filter { marker -> GroupPolicy.visible(now, marker.receivedAt) }
                    .map { marker -> marker.copy(delayed = GroupPolicy.delayed(now, marker.receivedAt)) }) }
                delay(1_000)
            }
        }
    }
    fun setScreenVisible(visible: Boolean) { screenVisible = visible; if (visible) lastStatusAt = 0 }
    fun prepareRoute(stops: List<RouteStop>, transport: Transport) {
        mutable.update { it.copy(preparedStops=stops.drop(1).take(12), transport=transport,screenRequested=true) }
    }
    fun handleInvite(uri: Uri) {
        if (uri.scheme == "iz" && uri.host == "group") {
            val code=uri.getQueryParameter("code")?.uppercase()?.takeIf { it.matches(Regex("[A-Z0-9]{8}")) } ?: return
            mutable.update { it.copy(inviteInput=code,screenRequested=true) }
        }
    }
    suspend fun routeFromCurrentLocation(): List<RouteStop>? {
        val fix = navigation.state.value.fix?.takeIf {
            clock() - it.recordedAt in 0..30_000 && !navigation.state.value.simulation
        } ?: runCatching { navigation.currentLocation() }.getOrNull() ?: return null
        return listOf(RouteStop("Konumum", fix.coordinate)) + mutable.value.stops
    }
    fun dismissScreenRequest() { mutable.update { it.copy(screenRequested=false) } }
    fun create(name: String) = action {
        require(name.isNotBlank() && name.trim().length <= 40)
        val stops=mutable.value.preparedStops
        if (stops.isEmpty()) throw GroupException("route_required")
        applyResponse(client.command("create",JSONObject().put("name",name.trim()).put("stops",JSONArray().apply {
            stops.forEach { put(JSONObject().put("label",it.label).put("latitude",it.coordinate.latitude).put("longitude",it.coordinate.longitude)) }
        })))
    }
    fun join(name: String, code: String) = action {
        applyResponse(client.command("join",JSONObject().put("name",name.trim()).put("code",code.trim().uppercase())))
    }
    fun approve(userId: String) = action { applyResponse(client.command("approve",args().put("user_id",userId))) }
    fun remove(userId: String) = action { applyResponse(client.command("remove",args().put("user_id",userId))) }
    fun renewInvite() = action { applyResponse(client.command("invite",args())) }
    fun refresh() = action { applyResponse(client.command("status",args().put("keepalive",mutable.value.sharing))) }
    fun leave() = requestExit("leave")
    fun end() = requestExit("end")
    private fun requestExit(command: String) {
        ++intentEpoch
        mutable.update { it.copy(pendingExit=command) }
        stopLocally()
        action { confirmExit(client.command(command,args())) }
    }
    private fun confirmExit(response: JSONObject) {
        if (!response.has("group") || !response.isNull("group")) throw GroupException("invalid_response")
        applyResponse(response)
    }
    fun setSharing(enabled: Boolean) {
        val requestedEpoch = ++intentEpoch
        if (!enabled) stopLocally()
        action {
            val session = navigation.state.value
            if (enabled && (session.sessionId == null || session.simulation || mutable.value.selfStatus != "approved" || mutable.value.pendingExit != null)) throw GroupException("session_required")
            val response=client.command("consent",args().put("enabled",enabled))
            if (requestedEpoch != intentEpoch) return@action
            if (enabled && navigation.state.value.sessionId != session.sessionId) {
                client.command("consent",args().put("enabled",false)); throw GroupException("session_required")
            }
            if (enabled) {
                sharingSession = session.sessionId
                navigation.setSharingLocation(true, session.sessionId)
                if (requestedEpoch != intentEpoch || navigation.state.value.sessionId != session.sessionId || !navigation.state.value.sharingLocation) {
                    stopLocally()
                    client.command("consent",args().put("enabled",false))
                    throw GroupException("session_required")
                }
                mutable.update { it.copy(sharing=true) }
            }
            applyResponse(response)
        }
    }
    private fun action(block: suspend () -> Unit) {
        scope.launch {
            commands.withLock {
                mutable.update { it.copy(busy=true,message=null) }
                try { block() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { handleFailure(e) }
                finally { mutable.update { it.copy(busy=false) } }
            }
        }
    }
    private fun args() = JSONObject().apply { mutable.value.groupId?.let { put("group_id",it) } }
    private fun stopLocally() {
        val sessionToStop = sharingSession
        val stopEpoch = intentEpoch
        sharingSession = null
        mutable.update { it.copy(sharing=false,connected=false,markers=emptyList()) }
        closeSocket()
        if (sessionToStop != null) scope.launch {
            if (stopEpoch == intentEpoch && !mutable.value.sharing) navigation.setSharingLocation(false, sessionToStop)
        }
    }
    private fun clearGroup(reason: String? = null) {
        stopLocally(); inbox=null
        mutable.update { GroupUiState(configured=it.configured,message=reason,screenRequested=it.screenRequested,inviteInput=it.inviteInput,preparedStops=it.preparedStops,transport=it.transport,canShare=it.canShare) }
    }
    private fun applyResponse(response: JSONObject) {
        if (!response.has("group")) throw GroupException("invalid_response")
        val group=response.optJSONObject("group") ?: run { clearGroup(); return }
        val status=group.getString("self_status")
        if (status !in listOf("pending","approved")) throw GroupException("invalid_response")
        val id=group.getString("id")
        val old=mutable.value
        if (old.groupId != null && old.groupId != id) stopLocally()
        val members=group.getJSONArray("members").let { list -> (0 until list.length()).map { i -> list.getJSONObject(i).let { GroupMember(it.getString("user_id"),it.getString("name"),it.getString("status"),it.optBoolean("consent")) } } }
        val stops=group.getJSONArray("stops").let { list -> (0 until list.length()).map { i -> list.getJSONObject(i).let { RouteStop(it.getString("label"),WeatherCoordinate(it.getDouble("latitude"),it.getDouble("longitude"))) } } }
        inbox=group.getString("inbox")
        if (mutable.value.sharing && !group.optBoolean("consent")) stopLocally()
        mutable.update { it.copy(groupId=id,selfId=response.getString("user_id"),hostId=group.getString("host_id"),expiresAt=group.getLong("expires_at"),
            inviteCode=group.optString("invite_code").takeUnless { code -> code == "null" }.orEmpty(),inviteExpiresAt=group.optLong("invite_expires_at"),
            selfStatus=status,members=members,stops=stops,markers=it.markers.filter { mark -> members.any { member -> member.userId==mark.userId && member.status=="approved" && member.consent } }) }
    }
    private fun closeSocket() {
        socketEpoch++
        val closing = socket
        socket = null
        socketInbox = null
        socketToken = null
        joinAcknowledged = false
        joinDeadline = 0L
        pendingHeartbeatRef = null
        heartbeatDeadline = 0L
        reconnectAt = clock() + 2_000
        mutable.update { it.copy(connected = false) }
        closing?.close(1000, "Closed")
        closing?.cancel()
    }
    private suspend fun ensureSocket() {
        val target = inbox ?: return
        val requestedEpoch = socketEpoch
        val token = client.token()
        // Auth may wait for HTTP refresh while consent/inbox/socket lifecycle changes.
        if (requestedEpoch != socketEpoch || !mutable.value.sharing || inbox != target) return
        if (socket != null && socketInbox == target && socketToken == token) return
        closeSocket()
        val epoch = socketEpoch
        socketInbox = target
        socketToken = token
        joinDeadline = clock() + 10_000
        socket = client.openSocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { scope.launch {
                if (epoch != socketEpoch || !mutable.value.sharing) { webSocket.cancel(); return@launch }
                val joined = webSocket.send(JSONObject().put("topic", "realtime:iz:inbox:$target").put("event", "phx_join").put("ref", "1").put("join_ref", "1")
                    .put("payload", JSONObject().put("access_token", token).put("config", JSONObject().put("private", true)
                        .put("broadcast", JSONObject().put("ack", false).put("self", false)).put("presence", JSONObject().put("enabled", false)))).toString())
                if (!joined) closeSocket()
            } }
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch {
                if (epoch != socketEpoch || !mutable.value.sharing) return@launch
                runCatching {
                    val data = JSONObject(text)
                    if (data.optString("event") == "phx_reply") {
                        val success = data.getJSONObject("payload").optString("status") == "ok"
                        if (data.optString("ref") == "1" && data.optString("topic") == "realtime:iz:inbox:$target") {
                            if (!success) { closeSocket(); return@runCatching }
                            joinAcknowledged = true
                            lastHeartbeatAt = clock()
                            mutable.update { it.copy(connected = true, message = null) }
                        } else if (pendingHeartbeatRef != null && data.optString("ref") == pendingHeartbeatRef && data.optString("topic") == "phoenix") {
                            if (!success) { closeSocket(); return@runCatching }
                            pendingHeartbeatRef = null
                            heartbeatDeadline = 0L
                        }
                    }
                    if (joinAcknowledged && data.optString("event") == "broadcast" && data.optString("topic") == "realtime:iz:inbox:$target")
                        acceptMarker(data.getJSONObject("payload").getJSONObject("payload"))
                    if (data.optString("event") in listOf("phx_error", "phx_close")) closeSocket()
                }
            } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch {
                if (epoch == socketEpoch) closeSocket()
            } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch {
                if (epoch == socketEpoch) closeSocket()
            } }
        })
    }
    private fun acceptMarker(payload: JSONObject) {
        val now=clock()
        val current=mutable.value
        if (!current.sharing || payload.getString("group_id")!=current.groupId || payload.getLong("sent_at") !in now-30_000..now+5_000) return
        val user=payload.getString("user_id")
        val member=current.members.firstOrNull { it.userId==user && it.status=="approved" && it.consent } ?: return
        val coordinate=WeatherCoordinate(payload.getDouble("latitude"),payload.getDouble("longitude"))
        val sent=payload.getLong("sent_at")
        if (current.markers.any { it.userId==user && it.receivedAt>=sent }) return
        mutable.update { it.copy(markers=it.markers.filterNot { marker -> marker.userId==user }+GroupMarker(user,member.name,coordinate.latitude,coordinate.longitude,receivedAt=sent)) }
    }
    fun dispose() { stopLocally(); scope.cancel() }
    private fun handleFailure(error: Exception) {
        if ((error as? GroupException)?.reason == "identity_changed") {
            ++intentEpoch
            clearGroup("Cihaz kimliği yenilendi. Gruba yeniden katıl; konum paylaşımı kapalı.")
        } else mutable.update { it.copy(message=message(error)) }
    }
    private fun message(error: Exception): String = if (mutable.value.pendingExit != null) "Paylaşım kapalı. Gruptan ayrılma veya bitirme sunucuda henüz doğrulanmadı; bağlantı gelince tekrar denenecek." else when ((error as? GroupException)?.reason) {
        "unconfigured" -> "Grup sunucusu henüz yapılandırılmadı."
        "route_required" -> "Önce haritada ortak hedef ve durakları planlayın."
        "session_required" -> "Konum paylaşmak için gerçek bir yolculuk veya navigasyon başlatın."
        "invalid_invite","invalid_code" -> "Davet kodu geçersiz veya süresi dolmuş."
        "group_full" -> "Grup en fazla 10 kişi olabilir."
        "already_in_group" -> "Önce mevcut gruptan ayrılın."
        "rate_limited" -> "Çok sık istek gönderildi. Biraz sonra tekrar deneyin."
        "approval_required","host_required","removed","consent_required" -> "Grup yetkisi veya paylaşım izni geçerli değil."
        else -> "Grup işlemi tamamlanamadı. İnternet bağlantısını kontrol edip tekrar deneyin."
    }
}








