package org.iz.navigation.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.iz.navigation.data.*
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlannedBreakCoordinatorTest {
    @Test fun automaticBreakKeepsRecorderAndGpsAndResumeRejectsOldOwner() = runTest {
        val f=Fixture(this);f.start()
        for(second in 0..45 step 5) f.feed(second)
        val paused=f.owner.state.value
        assertTrue(paused.paused);assertEquals(1,paused.plannedBreak!!.stopIndex)
        assertEquals(45.0,paused.plannedBreak.elapsedSeconds,0.0)
        assertTrue(paused.recording);assertEquals("break-recording",f.runtime.recordingId())
        assertTrue(f.runtime.location);assertEquals(0,f.runtime.finished)
        assertEquals(75.0,paused.tripTiming!!.breakSeconds-120.0,0.0)
        val snapshot=NavigationStopSnapshot.from(paused)!!
        assertFalse(f.owner.resumePlannedBreak(snapshot.copy(routeRevision=snapshot.routeRevision-1)))
        assertTrue(f.owner.resumePlannedBreak(snapshot))
        assertFalse(f.owner.resumePlannedBreak(snapshot))
        val resumed=f.owner.state.value
        assertFalse(resumed.paused);assertNull(resumed.plannedBreak)
        assertEquals(120.0,resumed.tripTiming!!.breakSeconds,0.0)
        assertEquals(0,f.runtime.finished);assertEquals(paused.sessionId,resumed.sessionId)
    }
    @Test fun manualPauseNeverTurnsIntoAutomaticResumeOrBreak() = runTest {
        val f=Fixture(this);f.start();f.feed(0)
        assertTrue(f.owner.setPaused(NavigationStopSnapshot.from(f.owner.state.value)!!,true))
        for(second in 5..60 step 5) f.feed(second,if(second<50).001 else .0015,4f)
        assertTrue(f.owner.state.value.paused);assertNull(f.owner.state.value.plannedBreak)
        assertNull(f.owner.state.value.tripTiming);assertEquals(0,f.runtime.finished)
    }
    @Test fun automaticResumeDoesNotRenderAnUnpausedOldProgressFrame() = runTest {
        val f=Fixture(this);f.start()
        for(second in 0..45 step 5) f.feed(second)
        f.runtime.rendered.clear()
        f.feed(50,.0013,3f);f.feed(55,.0015,3f)
        val frames=f.runtime.rendered.filter{!it.paused}
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all{it.progress!!.elapsedSeconds>100.0})
        assertNull(f.owner.state.value.plannedBreak)
        assertEquals(0,f.runtime.finished)
    }
    @Test fun positiveBreakWithoutBoundariesCannotStartARecording() = runTest {
        val f=Fixture(this)
        assertThrows(IllegalArgumentException::class.java) { f.route.copy(stopElapsedSeconds=emptyList()) }
        assertEquals(0,f.runtime.started)
    }
    private class Fixture(scope:TestScope) {
        var now=100_000L
        val route=run {
            val points=(0..3).map{WeatherCoordinate(0.0,it*.001)}
            PlannedRoute("two-breaks",points.mapIndexed{i,p->RouteStop("Stop$i",p,if(i in 1..2)120 else 0)},
                points.mapIndexed{i,p->RouteVertex(p,i*100.0)},333.0,300.0,now,
                stopElapsedSeconds=listOf(0.0,100.0,200.0,300.0),transport=Transport.CAR,
                maneuvers=listOf(RouteManeuver(10,"Dön","Dön",emptyList(),1,3,100.0,300.0)))
        }
        val runtime=Runtime{now}
        val owner=JourneyNavigationCoordinator(runtime,null,{now},scope.backgroundScope,monotonicClock={now})
        suspend fun start(){ owner.startGuidance(route) }
        suspend fun feed(second:Int,lon:Double=.001,speed:Float=0f) {
            now=100_000L+second*1000L
            val fix=NavigationFix(WeatherCoordinate(0.0,lon),now,3f,speed,90f)
            owner.onAcceptedLocation("break-recording",fix)
            while(owner.state.value.fix?.recordedAt!=fix.recordedAt) yield()
        }
    }
    private class Runtime(val now:()->Long):NavigationRuntime {
        override val journeys=MutableStateFlow<List<Journey>>(emptyList())
        var running:String?=null;var started=0;var finished=0;var location=false
        val rendered=mutableListOf<NavigationState>()
        override suspend fun activeJourney()=journeys.value.firstOrNull{it.endedAt==null}
        override suspend fun start(transport:Transport,stillCurrent:()->Boolean):String {
            check(stillCurrent());started++;running="break-recording"
            journeys.value=listOf(Journey(id=running!!,transport=transport,status=JourneyStatus.CONFIRMED,startedAt=now()))
            return running!!
        }
        override suspend fun confirm(id:String,transport:Transport)=Unit
        override suspend fun finish(id:String){finished++;running=null}
        override suspend fun interrupt(id:String){finished++;running=null}
        override fun recordingId()=running
        override fun locationActive()=location
        override fun setLocationSession(id:String?,transport:Transport?,highFrequency:Boolean,suppressAutomatic:Boolean){location=id!=null}
        override fun setHighFrequency(journeyId:String?)=Unit
        override suspend fun plan(stops:List<RouteStop>,transport:Transport):PlannedRoute=error("No provider in synthetic scenario")
        override suspend fun locate()=NavigationFix(WeatherCoordinate(0.0,0.0),now(),3f,0f)
        override fun render(state:NavigationState){rendered+=state}
        override fun speak(text:String)=Unit
        override fun cancelSpeech()=Unit
    }
}
