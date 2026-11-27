package org.iz.navigation.lean

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LeanLiveDecisionCodecTest {
    private val measured = LeanLiveDecisionCode.ACCEPTED_MEASURED
    private val stale = LeanLiveDecisionCode.GNSS_STALE
    private val calibration = LeanCalibration("live-epoch", 1, listOf(0.0, 0.0, 9.80665),
        listOf(0.0, 0.0, 0.0), false, listOf(0.0, 0.0, 1.0))

    private fun live(summary: LeanLiveDecisionSummary = LeanLiveDecisionSummary()): LeanBackupSession {
        val input = LeanInputSession(LeanSessionToken("12345678-1234-1234-1234-123456789abc", "trip", 1),
            calibration, "live-only:owner", 1, 100, 200, "", emptySet(), 0)
        return LeanBackupSession(input.copy(inputHash = LeanCodec.inputHash(input, emptyList())),
            "LIVE_ONLY", emptyList(), liveDecisions = summary)
    }

    @Test fun mapperCoversEveryProducerCodeAndPrefersSpecificGnssIssue() {
        val expected = LiveHandlebarReason.entries.map { it.name }.toSet() +
            LeanGnssIssueCode.entries.map { it.name } + setOf("ACCEPTED_MEASURED", "ACCEPTED_ESTIMATED")
        assertEquals(expected, LeanLiveDecisionCode.entries.map { it.name }.toSet())
        LiveHandlebarReason.entries.forEach { reason ->
            assertEquals(reason.name, LeanLiveDecisionCode.from(LiveHandlebarDecision.Rejected(reason)).name)
        }
        LeanGnssIssueCode.entries.forEach { issue ->
            assertEquals(issue.name, LeanLiveDecisionCode.from(
                LiveHandlebarDecision.Rejected(LiveHandlebarReason.GNSS_QUALITY, issue)).name)
        }
        LiveHandlebarSource.entries.forEach { source ->
            assertEquals("ACCEPTED_${source.name}", LeanLiveDecisionCode.from(
                LiveHandlebarDecision.Accepted(12.0, source, 123)).name)
        }
    }

    @Test fun summaryCopiesCallerMapAndEnforcesOpenedAndTotalBudget() {
        val mutable = mutableMapOf(measured to 2L, stale to 0L)
        val summary = LeanLiveDecisionSummary(true, mutable)
        mutable[measured] = 99
        assertEquals(mapOf(measured to 2L), summary.counts)
        assertThrows(UnsupportedOperationException::class.java) { (summary.counts as MutableMap)[stale] = 1L }
        assertThrows(IllegalArgumentException::class.java) { LeanLiveDecisionSummary(false, mapOf(stale to 1)) }
        assertThrows(IllegalArgumentException::class.java) { LeanLiveDecisionSummary(true, mapOf(stale to -1)) }
        assertThrows(IllegalArgumentException::class.java) {
            LeanLiveDecisionSummary(true, mapOf(measured to LeanLiveDecisionSummary.MAX_DECISIONS, stale to 1))
        }
        assertEquals(10_000_000L, LeanLiveDecisionSummary(true, mapOf(stale to 10_000_000)).total)
    }

    @Test fun cumulativeCheckpointsAllowIdempotenceAndGrowthButRejectRegression() {
        val unopened = LeanLiveDecisionSummary()
        val opened = LeanLiveDecisionSummary(true)
        val first = LeanLiveDecisionSummary(true, mapOf(measured to 2))
        assertTrue(unopened.follows(unopened))
        assertTrue(opened.follows(unopened))
        assertFalse(unopened.follows(opened))
        assertTrue(first.follows(opened))
        assertTrue(first.follows(first))
        assertTrue(LeanLiveDecisionSummary(true, mapOf(measured to 2, stale to 1)).follows(first))
        assertFalse(LeanLiveDecisionSummary(true, mapOf(measured to 1, stale to 2)).follows(first))
    }

    @Test fun codecRoundTripsDistinctNeverOpenedAndOpenedWithoutDecisions() {
        listOf(LeanLiveDecisionSummary(), LeanLiveDecisionSummary(true),
            LeanLiveDecisionSummary(true, mapOf(measured to 7, stale to 1))).forEach { summary ->
            val encoded = LeanCodec.liveDecisionsJson(summary)
            assertEquals(summary, LeanCodec.liveDecisions(encoded))
            assertEquals(setOf("version", "dialOpened", "counts"), JSONObject(encoded).keys().asSequence().toSet())
        }
        assertNotEquals(LeanLiveDecisionSummary(), LeanLiveDecisionSummary(true))
    }

    @Test fun codecRejectsUnknownFieldsCodesAndNonIntegralOrOutOfRangeCounts() {
        val bad = listOf(
            "{\"version\":1,\"dialOpened\":true,\"counts\":{\"OTHER\":1}}",
            "{\"version\":1,\"dialOpened\":true,\"counts\":{\"GNSS_STALE\":1.5}}",
            "{\"version\":1,\"dialOpened\":true,\"counts\":{\"GNSS_STALE\":\"1\"}}",
            "{\"version\":1,\"dialOpened\":true,\"counts\":{\"GNSS_STALE\":-1}}",
            "{\"version\":1,\"dialOpened\":true,\"counts\":{\"GNSS_STALE\":10000001}}",
            "{\"version\":1,\"dialOpened\":false,\"counts\":{\"GNSS_STALE\":1}}",
            "{\"version\":1,\"dialOpened\":\"true\",\"counts\":{}}",
            "{\"version\":2,\"dialOpened\":true,\"counts\":{}}",
            "{\"version\":1,\"dialOpened\":true,\"counts\":{},\"latitude\":0}")
        bad.forEach { raw -> assertThrows(raw, Exception::class.java) { LeanCodec.liveDecisions(raw) } }
    }

    @Test fun liveOnlyBackupPreservesSummaryWithoutRawAndKeepsInputIdentitySeparate() {
        val session = live(LeanLiveDecisionSummary(true, mapOf(measured to 3, stale to 1)))
        val decoded = LeanBackupCodec.decode(LeanBackupCodec.encode(listOf(session))).single()
        assertEquals(session, decoded)
        assertNull(decoded.result)
        assertTrue(decoded.chunks.isEmpty())
        val changed = session.copy(liveDecisions = LeanLiveDecisionSummary(true, mapOf(measured to 4)))
        assertEquals(session.input.inputHash, LeanBackupCodec.decode(LeanBackupCodec.encode(listOf(changed))).single().input.inputHash)
        val corrupt = LeanBackupCodec.encode(listOf(session))
        corrupt.getJSONObject(0).getJSONObject("input").put("endedAtElapsedNanos", 201)
        assertThrows(IllegalArgumentException::class.java) { LeanBackupCodec.decode(corrupt) }
    }

    @Test fun legacyBackupWithoutSummaryRemainsUnknownAndOmitsNewField() {
        val raw = live().copy(state = "PARTIAL", liveDecisions = null)
        val encoded = LeanBackupCodec.encode(listOf(raw))
        assertFalse(encoded.getJSONObject(0).has("liveDecisions"))
        assertNull(LeanBackupCodec.decode(encoded).single().liveDecisions)
        encoded.getJSONObject(0).put("liveDecisions", JSONObject.NULL)
        assertNull(LeanBackupCodec.decode(encoded).single().liveDecisions)
    }

    @Test fun liveOnlyRejectsMissingSummaryRawPartsResultsAndBodyCalibration() {
        val session = live()
        val invalid = listOf(session.copy(liveDecisions = null),
            session.copy(state = "PARTIAL"),
            session.copy(chunks = listOf(LeanChunkManifest(0, 1, "0".repeat(64), 1, 100, 100))),
            session.copy(result = LeanEstimate(HandlebarLeanEstimator.VERSION, calibration.epoch, null, null, 0, 0, emptySet(), emptyList())),
            session.copy(input = session.input.copy(calibration = calibration.copy(bodyConfirmed = true, steeringAxis = emptyList()))))
        invalid.forEach { value -> assertThrows(Exception::class.java) { LeanBackupCodec.encode(listOf(value)) } }
    }
}
