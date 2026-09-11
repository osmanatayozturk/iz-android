package org.iz.navigation.health

import org.junit.Assert.*
import org.junit.Test
import org.iz.navigation.wearprotocol.WearDailyHealthStatus.*

class DailyActivityHealthRulesTest {
    @Test fun unionPreventsDuplicateExerciseTimeAndDistanceQueries() {
        assertEquals(listOf(DailyRange(10, 50)), DailyActivityHealthRules.ranges(listOf(DailyRange(0, 30), DailyRange(20, 60), DailyRange(0, 30)), emptyList(), DailyRange(10, 50)))
    }
    @Test fun knownCyclingOverlapIsNeverAttributedToWalking() {
        assertEquals(listOf(DailyRange(0, 20), DailyRange(40, 60)), DailyActivityHealthRules.ranges(listOf(DailyRange(0, 60)), listOf(DailyRange(20, 40)), DailyRange(0, 100)))
    }
    @Test fun missingDiffersFromMeasuredZeroAndPartial() {
        assertEquals(UNAVAILABLE, DailyActivityHealthRules.distanceStatus(listOf(null)))
        assertEquals(AVAILABLE, DailyActivityHealthRules.distanceStatus(listOf(0.0)))
        assertEquals(PARTIAL, DailyActivityHealthRules.distanceStatus(listOf(0.0, null)))
    }
}
