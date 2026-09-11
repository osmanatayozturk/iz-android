package com.atay.iz.watch

import org.junit.Assert.*
import org.junit.Test

class WatchHealthResumePolicyTest {
    @Test fun onlyExplicitArmingAndValidPermissionsAllowAutomaticRecovery() {
        assertTrue(WatchHealthResumePolicy.shouldResume(true, true, false))
        assertFalse(WatchHealthResumePolicy.shouldResume(false, true, false))
        assertFalse(WatchHealthResumePolicy.shouldResume(true, false, false))
        assertFalse(WatchHealthResumePolicy.shouldResume(true, true, true))
    }
    @Test fun rebootPreservesIntentButNeverReusesPriorBootSensorClock() {
        assertTrue(WatchHealthResumePolicy.shouldResume(true, true, false))
        assertTrue(WatchHealthResumePolicy.canReuseClock(12, 12))
        assertFalse(WatchHealthResumePolicy.canReuseClock(12, 13))
        assertFalse(WatchHealthResumePolicy.canReuseClock(-1, -1))
    }
}
