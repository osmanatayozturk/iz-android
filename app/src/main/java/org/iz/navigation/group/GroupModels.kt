package org.iz.navigation.group

import org.iz.navigation.data.Transport
import org.iz.navigation.weather.RouteStop
import org.json.JSONObject

object GroupPolicy {
    fun shouldPublish(now: Long, fixAt: Long, speed: Float, lastSentAt: Long): Boolean =
        fixAt in (now - 30_000)..(now + 5_000) && now - lastSentAt >= if (speed >= 1f) 10_000 else 30_000
    fun delayed(now: Long, receivedAt: Long) = now - receivedAt > 30_000
    fun visible(now: Long, receivedAt: Long) = now - receivedAt <= 300_000
}
object GroupWire {
    fun requireSuccess(status: Int, text: String): JSONObject {
        val value = runCatching { JSONObject(text) }.getOrElse { throw GroupException("invalid_response") }
        if (status !in 200..299 || value.opt("ok") != true) throw GroupException(value.optString("error", "request_failed"))
        return value
    }
}
class GroupException(val reason: String) : Exception(reason)
data class GroupMember(val userId: String, val name: String, val status: String, val consent: Boolean)
data class GroupMarker(val userId: String, val name: String, val latitude: Double, val longitude: Double, val delayed: Boolean = false, val receivedAt: Long)
data class GroupUiState(
    val pendingExit: String? = null,
    val configured: Boolean = false, val busy: Boolean = false, val message: String? = null,
    val screenRequested: Boolean = false, val inviteInput: String = "", val selfId: String = "",
    val groupId: String? = null, val hostId: String = "", val expiresAt: Long = 0,
    val inviteCode: String = "", val inviteExpiresAt: Long = 0, val selfStatus: String = "",
    val sharing: Boolean = false, val connected: Boolean = false, val canShare: Boolean = false,
    val members: List<GroupMember> = emptyList(), val markers: List<GroupMarker> = emptyList(),
    val stops: List<RouteStop> = emptyList(), val preparedStops: List<RouteStop> = emptyList(),
    val transport: Transport = Transport.MOTORCYCLE,
) { val isHost: Boolean get() = selfId.isNotEmpty() && selfId == hostId }

