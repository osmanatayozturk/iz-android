package org.iz.navigation.data

import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test

class LargePlanBackupTest {
    private fun plan(count: Int) = SavedRoutePlan(id="large", name="Sentetik", transport=Transport.CAR, createdAt=1,
        stops=List(count) { i -> RouteStop(if (i % 2 == 0) "A" else "B", WeatherCoordinate(10.0,20.0), if (i in 1 until count-1) 60 else 0) })
    @Test fun twentyStopBackupRoundTripsDuplicatesBreaksAndDynamicOrigin() {
        val saved = plan(20).copy(originUsesCurrentLocation=true)
        val value = BackupJson.encode(DiarySnapshot(savedPlans=listOf(saved)))
        assertEquals(12, value.getInt("version"))
        assertEquals(saved, BackupJson.decode(value).savedPlans.single())
    }
    @Test fun twentyOnePlannedStopsAreRejectedRatherThanTruncated() {
        assertThrows(IllegalArgumentException::class.java) { BackupJson.encode(DiarySnapshot(savedPlans=listOf(plan(21)))) }
    }
    @Test fun oldVersionsRemainReadableButCannotClaimExpandedFormat() {
        for (version in 7..10) {
            val old = BackupJson.encode(DiarySnapshot(savedPlans=listOf(plan(5)))).put("version",version)
            assertEquals(plan(5), BackupJson.decode(old).savedPlans.single())
            old.getJSONArray("savedPlans").getJSONObject(0).put("stops", TravelLibraryJson.stops(plan(6).stops))
            assertThrows(IllegalArgumentException::class.java) { BackupJson.decode(old) }
        }
    }
}
