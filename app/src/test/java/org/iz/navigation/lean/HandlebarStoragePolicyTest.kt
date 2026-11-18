package org.iz.navigation.lean

import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HandlebarStoragePolicyTest {
    private val body = LeanCalibration("mount-1", 1L, listOf(0.0, 0.0, 9.81), listOf(0.0, 0.0, 0.0))
    private val bars = body.copy(bodyConfirmed = false, steeringAxis = listOf(0.0, 0.6, 0.8))
    private val journey = Journey(id = "trip", transport = Transport.MOTORCYCLE, startedAt = 1L)

    @Test fun handlebarProfileRequiresMatchingMountAndExplicitPackageGate() {
        val settings = LeanCaptureSettings(true, LeanMount.HANDLEBAR, bars)
        assertTrue(leanCaptureEligible(journey, settings, true))
        assertFalse(leanCaptureEligible(journey, settings, false))
        assertFalse(leanCaptureEligible(journey, settings.copy(mount = LeanMount.BODY), true))
        assertFalse(leanCaptureEligible(journey, settings.copy(calibration = body), true))
        assertFalse(leanCaptureEligible(journey, settings.copy(calibration = bars.copy(steeringAxis = listOf(0.0, 0.0, 0.0))), true))
        assertTrue(leanCaptureEligible(journey, LeanCaptureSettings(true, LeanMount.BODY, body), false))
    }

    @Test fun legacyBodyAndVerifiedHandlebarBackupsKeepDistinctIdentity() {
        val legacy = """{"version":1,"epoch":"mount-1","createdAt":1,"gravity":[0,0,9.81],"bias":[0,0,0],"body":true}"""
        assertEquals(body, LeanCodec.calibration(legacy))
        assertEquals(1, JSONObject(LeanCodec.calibrationJson(body)).getInt("version"))
        assertEquals(bars, LeanCodec.calibration(LeanCodec.calibrationJson(bars)))
        val input = LeanInputSession(LeanSessionToken("11111111-2222-4333-8444-555555555555", "trip", 1),
            bars, "boot", 10, 20, 30, "", emptySet(), 0)
        val original = input.copy(inputHash = LeanCodec.inputHash(input, emptyList()))
        assertEquals("c0e11589074ae8b3821f71859e5bbd69e8b5d2b0a5db2098f4a542d6e470d755",
            LeanCodec.inputHash(input.copy(calibration = body), emptyList()))
        val pending = LeanBackupSession(original, "SEALED", emptyList())
        assertEquals(listOf(pending), LeanBackupCodec.decode(LeanBackupCodec.encode(listOf(pending))))
        val swapped = bars.copy(steeringAxis = bars.steeringAxis.map { -it })
        assertNotEquals(original.inputHash, LeanCodec.inputHash(input.copy(calibration = swapped), emptyList()))
    }
}
