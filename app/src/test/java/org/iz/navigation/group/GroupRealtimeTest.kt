package org.iz.navigation.group

import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupRealtimeTest {
    private class Socket(val listener: WebSocketListener, val acknowledgeJoin:Boolean, val acknowledgeHeartbeat:Boolean):WebSocket {
        val sent=mutableListOf<JSONObject>()
        var cancelled=false
        override fun request()=Request.Builder().url("https://example.test").build()
        override fun queueSize()=0L
        override fun send(text:String):Boolean {
            val message=JSONObject(text);sent+=message
            if(message.optString("event")=="phx_join" && acknowledgeJoin || message.optString("event")=="heartbeat" && acknowledgeHeartbeat) acknowledge(message)
            return true
        }
        fun acknowledge(message:JSONObject) { listener.onMessage(this,JSONObject().put("event","phx_reply").put("topic",message.getString("topic")).put("ref",message.getString("ref")).put("payload",JSONObject().put("status","ok")).toString()) }
        override fun send(bytes:ByteString)=true
        override fun close(code:Int,reason:String?):Boolean { cancelled=true;return true }
        override fun cancel() { cancelled=true }
    }
    private class Backend(val acknowledgeJoin:Boolean=true,val acknowledgeHeartbeat:Boolean=true):GroupBackend {
        override val configured=true
        override val hasIdentity=false
        val sockets=mutableListOf<Socket>()
        var metadataGate:CompletableDeferred<Unit>?=null
        var metadataWaiting=false
        override suspend fun token()="token"
        override suspend fun command(action:String,body:JSONObject):JSONObject {
            if(action=="status" && metadataGate!=null) {metadataWaiting=true;metadataGate!!.await()}
            val consent=(action=="consent" && body.optBoolean("enabled")) || (action=="status" && body.optBoolean("keepalive"))
            return JSONObject("""{"ok":true,"user_id":"host","group":{"id":"ride","host_id":"host","expires_at":90000000,"invite_code":"AB12CD34","invite_expires_at":900000,"self_status":"approved","consent":$consent,"inbox":"inbox","stops":[{"label":"End","latitude":0,"longitude":0}],"members":[{"user_id":"host","name":"Host","status":"approved","consent":$consent}]}}""")
        }
        override fun openSocket(listener:WebSocketListener):WebSocket {
            val socket=Socket(listener,acknowledgeJoin,acknowledgeHeartbeat);sockets+=socket
            listener.onOpen(socket,Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
            return socket
        }
    }
    private class Navigation:GroupNavigation {
        override val state=MutableStateFlow(NavigationState(sessionId="trip"))
        override suspend fun currentLocation()=NavigationFix(WeatherCoordinate(0.0,0.0),100000,1f)
        override suspend fun setSharingLocation(enabled:Boolean,expectedSessionId:String?) { if(expectedSessionId==state.value.sessionId)state.value=state.value.copy(sharingLocation=enabled) }
    }
    private fun TestScope.startSharing(backend:Backend):GroupCoordinator {
        val group=GroupCoordinator(backend,Navigation(),backgroundScope) { testScheduler.currentTime+100000 }
        group.prepareRoute(listOf(RouteStop("Origin",WeatherCoordinate(0.0,0.0)),RouteStop("End",WeatherCoordinate(0.0,0.0))),Transport.WALK)
        group.create("Host");runCurrent();group.setSharing(true);runCurrent();advanceTimeBy(1000);runCurrent()
        return group
    }
    @Test fun silentJoinIsReplacedAndLateOldJoinAckCannotClaimConnected()=runTest {
        val backend=Backend(acknowledgeJoin=false)
        val group=startSharing(backend)
        val first=backend.sockets.first()
        advanceTimeBy(15000);runCurrent()
        assertTrue("Unacknowledged join must close",first.cancelled)
        assertTrue("Join deadline must trigger reconnect",backend.sockets.size>=2)
        first.acknowledge(first.sent.first { it.optString("event")=="phx_join" });runCurrent()
        assertFalse(group.state.value.connected)
        group.dispose()
    }
    @Test fun heartbeatTimeoutReconnectsEvenWhileMetadataHttpIsSuspended()=runTest {
        val backend=Backend(acknowledgeHeartbeat=false)
        val group=startSharing(backend)
        assertTrue(group.state.value.connected)
        val first=backend.sockets.first()
        backend.metadataGate=CompletableDeferred()
        advanceTimeBy(40000);runCurrent()
        assertTrue(backend.metadataWaiting)
        assertTrue("Silent connection must expire independently of HTTP mutex",first.cancelled)
        assertTrue("Working socket authentication can reconnect while metadata waits",backend.sockets.size>=2)
        group.dispose()
    }
    @Test fun matchingJoinAndHeartbeatAcknowledgementsKeepHealthySocket()=runTest {
        val backend=Backend()
        val group=startSharing(backend)
        advanceTimeBy(90000);runCurrent()
        assertEquals(1,backend.sockets.size)
        assertFalse(backend.sockets.single().cancelled)
        assertTrue(group.state.value.connected)
        assertTrue(backend.sockets.single().sent.count { it.optString("event")=="heartbeat" }>=3)
        group.dispose()
    }
}
