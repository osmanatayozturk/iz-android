package com.atay.iz.watch

import org.junit.Assert.*
import org.junit.Test

class WatchHealthPermissionsTest {
    @Test fun apiThirtyUsesBodySensorsWithoutNonexistentBackgroundPermission() {
        assertEquals(setOf("android.permission.BODY_SENSORS", "android.permission.ACTIVITY_RECOGNITION"),
            WatchHealthPermissions.required(30))
        assertNull(WatchHealthPermissions.background(30))
    }
    @Test fun apiThirtyThreeThroughThirtyFiveRequestsLegacyBackgroundSeparately() {
        for (api in 33..35) {
            assertEquals("android.permission.BODY_SENSORS_BACKGROUND", WatchHealthPermissions.background(api))
            assertTrue("android.permission.BODY_SENSORS" in WatchHealthPermissions.foreground(api))
            assertFalse(WatchHealthPermissions.background(api) in WatchHealthPermissions.foreground(api))
            assertTrue(WatchHealthPermissions.hasRequired(api) { it in setOf("android.permission.BODY_SENSORS",
                "android.permission.BODY_SENSORS_BACKGROUND", "android.permission.ACTIVITY_RECOGNITION") })
        }
    }
    @Test fun apiThirtySixAcceptsOnlyModernGrantsEvenWhenLegacyPermissionsWereRevokedByUpdate() {
        val modern = setOf("android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND", "android.permission.ACTIVITY_RECOGNITION")
        assertEquals(modern, WatchHealthPermissions.required(36))
        assertTrue(WatchHealthPermissions.hasRequired(36) { it in modern })
        assertFalse(WatchHealthPermissions.hasRequired(36) { it in setOf("android.permission.BODY_SENSORS",
            "android.permission.BODY_SENSORS_BACKGROUND", "android.permission.ACTIVITY_RECOGNITION") })
        assertEquals("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND", WatchHealthPermissions.background(36))
        assertFalse(WatchHealthPermissions.background(36) in WatchHealthPermissions.foreground(36))
    }
    @Test fun modernBackgroundGrantAndPhysicalActivityAreBothRequired() {
        assertFalse(WatchHealthPermissions.hasRequired(36) { it != "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" })
        assertFalse(WatchHealthPermissions.hasRequired(36) { it != "android.permission.ACTIVITY_RECOGNITION" })
    }
}
