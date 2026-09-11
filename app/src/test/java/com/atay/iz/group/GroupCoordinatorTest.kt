package com.atay.iz.group

import com.atay.iz.navigation.NavigationFix
import com.atay.iz.navigation.NavigationState
import com.atay.iz.weather.WeatherCoordinate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import com.atay.iz.weather.RouteStop
import com.atay.iz.data.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Request
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupCoordinatorTest {
    private class Backend(override val hasIdentity: Boolean = false): GroupBackend {
        override val configured = true
        val actions=mutableListOf<String>()
        var answer: suspend (String,JSONObject)->JSONObject = {_,_->JSONObject().put("ok",true).put("group",JSONObject.NULL)}
        override suspend fun command(action:String,body:JSONObject):JSONObject { actions+=action; return answer(action,body) }
        override suspend fun token()="token"
        override fun openSocket(listener:WebSocketListener):WebSocket = object:WebSocket {
            override fun request()=Request.Builder().url("https://example.test").build()
            override fun queueSize()=0L
            override fun send(text:String)=true
            override fun send(bytes:ByteString)=true
            override fun close(code:Int,reason:String?)=true
            override fun cancel() {}
        }
    }
    private class Navigation: GroupNavigation {
        override val state=MutableStateFlow(NavigationState())
        val sharingCalls=mutableListOf<Pair<Boolean,String?>>()
        override suspend fun setSharingLocation(enabled:Boolean, expectedSessionId:String?) { sharingCalls+=enabled to expectedSessionId; if(expectedSessionId==state.value.sessionId) state.value=state.value.copy(sharingLocation=enabled) }
        override suspend fun currentLocation()=NavigationFix(WeatherCoordinate(0.0,0.0),100_000,1f)
    }
    @Test fun untouchedIdleAppDoesNotCreateAnonymousIdentityOrPoll()=runTest {
        val backend=Backend()
        val group=GroupCoordinator(backend,Navigation(),backgroundScope) { testScheduler.currentTime+100_000 }
        advanceTimeBy(60_000); runCurrent()
        assertTrue(backend.actions.isEmpty())
        group.dispose()
    }
    @Test fun existingIdentityRestoresOnceWithoutEndlessIdlePolling()=runTest {
        val backend=Backend(true)
        val group=GroupCoordinator(backend,Navigation(),backgroundScope) { testScheduler.currentTime+100_000 }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("status"),backend.actions)
        group.dispose()
    }
    @Test fun explicitGroupScreenPollsWhileVisibleAndStopsWhenClosed()=runTest {
        val backend=Backend()
        val group=GroupCoordinator(backend,Navigation(),backgroundScope) { testScheduler.currentTime+100_000 }
        group.setScreenVisible(true); advanceTimeBy(31_000); runCurrent()
        assertEquals(3,backend.actions.size)
        group.setScreenVisible(false); advanceTimeBy(60_000); runCurrent()
        assertEquals(3,backend.actions.size)
        group.dispose()
    }
    private fun groupResponse(consent:Boolean=false) = JSONObject("""{"ok":true,"user_id":"host","group":{"id":"ride","host_id":"host","expires_at":90000000,"invite_code":"AB12CD34","invite_expires_at":900000,"self_status":"approved","consent":$consent,"inbox":"inbox","stops":[{"label":"End","latitude":0,"longitude":0}],"members":[{"user_id":"host","name":"Host","status":"approved","consent":$consent}]}}""")
    @Test fun stopDuringPendingConsentCannotEnableSharingFromLateResponse()=runTest {
        val backend=Backend(); val nav=Navigation()
        nav.state.value=nav.state.value.copy(sessionId="trip")
        val group=GroupCoordinator(backend,nav,backgroundScope) { testScheduler.currentTime+100_000 }
        backend.answer={_,_->groupResponse()}
        group.prepareRoute(listOf(RouteStop("Origin",WeatherCoordinate(0.0,0.0)),RouteStop("End",WeatherCoordinate(0.0,0.0))),Transport.WALK)
        group.create("Host"); runCurrent()
        val gate=CompletableDeferred<Unit>()
        backend.answer={action,args->if(action=="consent" && args.optBoolean("enabled")) { gate.await();groupResponse(true) } else groupResponse(false)}
        group.setSharing(true); runCurrent()
        group.setSharing(false); runCurrent()
        gate.complete(Unit); runCurrent()
        assertFalse(group.state.value.sharing)
        assertTrue(nav.sharingCalls.none { it.first })
        group.dispose()
    }
    @Test fun failedLeaveKeepsExplicitPendingExitAndNeverRestoresSharing()=runTest {
        val backend=Backend(); val group=GroupCoordinator(backend,Navigation(),backgroundScope) { testScheduler.currentTime+100_000 }
        backend.answer={_,_->groupResponse()}
        group.prepareRoute(listOf(RouteStop("Origin",WeatherCoordinate(0.0,0.0)),RouteStop("End",WeatherCoordinate(0.0,0.0))),Transport.WALK)
        group.create("Host"); runCurrent()
        backend.answer={action,_->if(action=="leave") throw GroupException("request_failed") else groupResponse(true)}
        group.leave(); runCurrent()
        assertEquals("leave",group.state.value.pendingExit)
        assertFalse(group.state.value.sharing)
        assertNotNull(group.state.value.message)
        backend.answer={_,_->JSONObject().put("ok",true).put("group",JSONObject.NULL)}
        advanceTimeBy(16_000);runCurrent()
        assertNull(group.state.value.groupId)
        assertNull(group.state.value.pendingExit)
        group.dispose()
    }
    @Test fun recentFixWithoutAcknowledgedLiveLocationIsNeverPublished()=runTest {
        val backend=Backend(); val nav=Navigation()
        nav.state.value=nav.state.value.copy(sessionId="trip",locationActive=false,gpsStale=false,fix=NavigationFix(WeatherCoordinate(0.0,0.0),100_000,1f,2f))
        val group=GroupCoordinator(backend,nav,backgroundScope) { testScheduler.currentTime+100_000 }
        backend.answer={action,args->groupResponse((action=="consent" && args.optBoolean("enabled")) || (action=="status" && args.optBoolean("keepalive")))}
        group.prepareRoute(listOf(RouteStop("Origin",WeatherCoordinate(0.0,0.0)),RouteStop("End",WeatherCoordinate(0.0,0.0))),Transport.WALK)
        group.create("Host");runCurrent()
        group.setSharing(true);runCurrent()
        advanceTimeBy(2_000);runCurrent()
        assertTrue(group.state.value.sharing)
        assertTrue(backend.actions.none { it=="publish" })
        group.dispose()
    }
}

