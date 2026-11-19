package org.iz.navigation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveLeanFreshnessTest {
    private val now = 12_000L
    private val reading = LiveLeanReading("ride", -18.0, now)

    @Test fun matchingFreshReadingPreservesSign() {
        assertEquals(-18.0, validLiveLeanDegrees(reading, "ride", now))
        assertEquals(18.0, validLiveLeanDegrees(reading.copy(signedDegrees = 18.0), "ride", now))
        assertEquals(0.0, validLiveLeanDegrees(reading.copy(signedDegrees = 0.0), "ride", now))
    }

    @Test fun readingExpiresAtTheFreshnessBoundary() {
        assertEquals(-18.0, validLiveLeanDegrees(reading, "ride", now + 1499))
        assertNull(validLiveLeanDegrees(reading, "ride", now + 1500))
    }

    @Test fun emptyAndDifferentSessionReadingsAreUnavailable() {
        assertNull(validLiveLeanDegrees(null, "ride", now))
        assertNull(validLiveLeanDegrees(reading, "next-ride", now))
    }

    @Test fun invalidMonotonicTimestampsAreUnavailable() {
        assertNull(validLiveLeanDegrees(reading.copy(measuredElapsedMillis = 0), "ride", now))
        assertNull(validLiveLeanDegrees(reading.copy(measuredElapsedMillis = now + 1), "ride", now))
    }

    @Test fun invalidAnglesAreUnavailable() {
        for (angle in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -65.1, 65.1)) {
            assertNull(validLiveLeanDegrees(reading.copy(signedDegrees = angle), "ride", now))
        }
    }
}
