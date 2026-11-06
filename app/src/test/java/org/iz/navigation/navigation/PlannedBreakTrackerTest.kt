package org.iz.navigation.navigation

import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class PlannedBreakTrackerTest {
    private fun route(mode:Transport=Transport.CAR):PlannedRoute {
        val points=(0..3).map { WeatherCoordinate(0.0,it*.01) }
        return PlannedRoute("breaks",points.mapIndexed { i,p->RouteStop("Same",p,if(i in 1..2)120 else 0) },
            points.mapIndexed { i,p->RouteVertex(p,i*100.0) },3300.0,300.0,0,
            stopElapsedSeconds=listOf(0.0,100.0,200.0,300.0),transport=mode)
    }
    private fun fix(at:Long,lon:Double=.01,speed:Float?=0f,accuracy:Float=3f)=NavigationFix(WeatherCoordinate(0.0,lon),at,accuracy,speed)
    private fun start(t:PlannedBreakTracker):Long {
        for(at in listOf(1_000L,21_000L,46_000L)) t.update(fix(at),at,{if(it==1)0.0 else 1100.0})
        assertNotNull(t.snapshot(46_000)); return 46_000
    }
    @Test fun allSixModesRequireStationaryConfirmationAndFiveSecondsOfProfileMovement() {
        for(mode in Transport.entries.filter { it!=Transport.UNKNOWN }) {
            val t=PlannedBreakTracker(route(mode)); val now=start(t)
            assertEquals(45.0,t.snapshot(now)!!.elapsedSeconds,0.0)
            val speed=when(mode){Transport.CAR,Transport.MOTORCYCLE,Transport.PASSENGER->2f;Transport.BICYCLE->1.2f;else->.7f}
            assertEquals(PlannedBreakTracker.Change.NONE,t.update(fix(now+1000,.0104,speed),now+1000,{0.0}))
            assertEquals(PlannedBreakTracker.Change.RESUMED,t.update(fix(now+6000,.0105,speed),now+6000,{0.0}))
            assertNull(t.snapshot(now+6000)); assertEquals(setOf(2),t.remaining(now+6000).keys)
        }
    }
    @Test fun missingSpeedNeedsMotionBeyondUncertaintyAndDistance() {
        val t=PlannedBreakTracker(route(Transport.WALK)); val now=start(t)
        assertEquals(PlannedBreakTracker.Change.NONE,t.update(fix(now+1000,.01012,null,20f),now+1000,{0.0}))
        assertEquals(PlannedBreakTracker.Change.NONE,t.update(fix(now+6000,.01012,null,20f),now+6000,{0.0}))
        assertNotNull(t.snapshot(now+6000))
        t.update(fix(now+7000,.0104,null),now+7000,{0.0})
        assertEquals(PlannedBreakTracker.Change.RESUMED,t.update(fix(now+12000,.0105,null),now+12000,{0.0}))
    }
    @Test fun routeCrossingAndTrafficWaitAwayFromPlannedStopDoNotTrigger() {
        val crossing=PlannedBreakTracker(route())
        for(at in listOf(1000L,21000L,46000L)) crossing.update(fix(at),at,{1000.0})
        assertNull(crossing.snapshot(46000))
        val traffic=PlannedBreakTracker(route())
        for(at in listOf(1000L,21000L,46000L)) traffic.update(fix(at,.005),at,{550.0})
        assertNull(traffic.snapshot(46000))
    }
    @Test fun duplicateOrImpreciseFixesCannotConfirmAndGpsLossPreservesActiveBreak() {
        val t=PlannedBreakTracker(route())
        t.update(fix(1000),1000,{0.0}); t.update(fix(1000),46000,{0.0})
        t.update(fix(46000,accuracy=30f),46000,{0.0})
        assertNull(t.snapshot(46000))
        val active=PlannedBreakTracker(route()); start(active); active.gpsUnavailable()
        assertNotNull(active.snapshot(180000)); assertEquals(0.0,active.snapshot(180000)!!.remainingSeconds,0.0)
        assertTrue(active.finish()); assertFalse(active.finish())
    }
    @Test fun candidateIsRetainedAfterGeometricRemainingListDropsTheVia() {
        val t=PlannedBreakTracker(route())
        t.update(fix(1000),1000,{if(it==1)-6.0 else 1000.0})
        assertEquals(listOf(1,2,3),t.retainedIndices(listOf(2,3)))
        t.update(fix(21000),21000,{if(it==1)-6.0 else 1000.0})
        assertEquals(PlannedBreakTracker.Change.STARTED,t.update(fix(46000),46000,{if(it==1)-6.0 else 1000.0}))
    }
    @Test fun passedStopIsConsumedWithoutChargingTheSecondBreakTwice() {
        val t=PlannedBreakTracker(route())
        t.update(fix(1000,.0105,8f),1000,{if(it==1)-55.0 else 1000.0})
        assertEquals(setOf(2),t.remaining(1000).keys)
        assertEquals(0,t.stopForRequest(1).plannedBreakSeconds)
        assertEquals(120,t.stopForRequest(2).plannedBreakSeconds)
    }
    @Test fun stopSpeedLimitsApplyToAllModes() {
        for(mode in Transport.entries.filter { it!=Transport.UNKNOWN }) {
            val t=PlannedBreakTracker(route(mode))
            for(at in listOf(1000L,21000L,46000L)) t.update(fix(at,speed=.9f),at,{0.0})
            assertNull(mode.name,t.snapshot(46000))
        }
    }
    @Test fun missingSpeedJumpThenStationaryFixesNeverResume() {
        val t=PlannedBreakTracker(route()); val now=start(t)
        for(offset in listOf(1000L,6000L,11000L,16000L))
            assertEquals(PlannedBreakTracker.Change.NONE,t.update(fix(now+offset,.011,null),now+offset,{0.0}))
        assertNotNull(t.snapshot(now+16000))
    }
    @Test fun missingSpeedFortyMetreDriftIsNotAStationaryCluster() {
        val t=PlannedBreakTracker(route())
        t.update(fix(1000,.01,null,20f),1000,{0.0})
        t.update(fix(21000,.01015,null,20f),21000,{-16.0})
        t.update(fix(46000,.0103,null,20f),46000,{-33.0})
        assertNull(t.snapshot(46000))
    }

}
