package org.iz.navigation.data

import org.iz.navigation.weather.*
import org.iz.navigation.navigation.NavigationRouteCache
import org.iz.navigation.navigation.prepareRouteStart
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlannedBreakCompatibilityTest {
    @get:Rule val temp=TemporaryFolder()
    private val points=(0..2).map{WeatherCoordinate(0.0,it*.01)}
    private fun stops(seconds:Int=1200)=points.mapIndexed{i,p->RouteStop("S$i",p,if(i==1)seconds else 0)}
    @Test fun savedPlanAndBackupEightRoundTripOptionalBreakMetadata() {
        val plan=SavedRoutePlan(id="break-plan",name="Break",stops=stops(),transport=Transport.CAR,createdAt=10)
        val encoded=BackupJson.encode(DiarySnapshot(savedPlans=listOf(plan)))
        assertEquals(8,encoded.getInt("version"))
        assertEquals(1200,BackupJson.decode(encoded).savedPlans.single().stops[1].plannedBreakSeconds)
        encoded.getJSONArray("savedPlans").getJSONObject(0).getJSONArray("stops").getJSONObject(1).remove("plannedBreakSeconds")
        assertEquals(0,BackupJson.decode(encoded).savedPlans.single().stops[1].plannedBreakSeconds)
    }
    @Test fun draftAndRouteCacheKeepLegacyZeroAndDoNotRestoreLiveBreak() {
        val draft=SavedWeatherPlan(stops(),1000,Transport.CAR)
        assertEquals(draft,WeatherPlanCodec.decode(WeatherPlanCodec.encode(draft)))
        val legacy=JSONObject(WeatherPlanCodec.encode(draft))
        legacy.getJSONArray("stops").getJSONObject(1).remove("plannedBreakSeconds")
        assertEquals(0,WeatherPlanCodec.decode(legacy.toString())!!.stops[1].plannedBreakSeconds)
        val route=PlannedRoute("cache",stops(),points.mapIndexed{i,p->RouteVertex(p,i*60.0)},2200.0,120.0,1000,
            stopElapsedSeconds=listOf(0.0,60.0,120.0),transport=Transport.CAR)
        val cache=NavigationRouteCache(temp.newFolder());cache.write(route)
        assertEquals(route,cache.read())
        assertEquals(0,RouteStop("Legacy",points[0]).plannedBreakSeconds)
    }
    @Test fun freshStartRejectsProviderThatDropsSavedBreaks() {
        val original=PlannedRoute("saved-start",stops(),points.mapIndexed{i,p->RouteVertex(p,i*60.0)},2200.0,120.0,1000,
            stopElapsedSeconds=listOf(0.0,60.0,120.0),transport=Transport.CAR)
        val planner=object:RoutePlanner {
            override suspend fun plan(stops:List<RouteStop>,departureAt:Long,transport:Transport,
                travelSpeedKmh:Double?,preferences:RoutePreferences):PlannedRoute =
                original.copy(stops=stops.map{it.copy(plannedBreakSeconds=0)})
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { prepareRouteStart(planner,original,points.first(),1000L) }
        }
    }
    @Test fun badOptionalValuesAndEndpointBreaksAreRejected() {
        val draft=SavedWeatherPlan(stops(),1000,Transport.CAR)
        for(bad in listOf(-1,14401,1.5,"60",true,JSONObject.NULL)){
            val json=JSONObject(WeatherPlanCodec.encode(draft));json.getJSONArray("stops").getJSONObject(1).put("plannedBreakSeconds",bad)
            assertNull(WeatherPlanCodec.decode(json.toString()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WeatherPlanCodec.encode(draft.copy(stops=draft.stops.toMutableList().also{it[0]=it[0].copy(plannedBreakSeconds=60)}))
        }
    }
}
