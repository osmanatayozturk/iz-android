package org.iz.navigation.placeinfo

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

/** Literal weekly oracles from the bound OSM semantics, independent of the editor converter. */
class BasicPlaceOpeningHoursTest {
    private fun result(raw: String?, at: String = "2026-11-09T10:00:00Z", zone: ZoneId = ZoneOffset.UTC) =
        BasicPlaceOpeningHours.evaluate(raw, Instant.parse(at), zone)

    private fun expect(raw: String, at: String, state: PlaceOpeningState, zone: ZoneId = ZoneOffset.UTC) {
        assertEquals("$raw at $at in $zone", state, result(raw, at, zone).state)
    }

    @Test fun missingAndPresentBlankRemainDistinctFromUnknownAndRetainOriginalRaw() {
        for (raw in listOf(null, "", " \t ")) {
            val value = result(raw)
            assertEquals(PlaceOpeningState.MISSING, value.state)
            assertEquals(raw, value.raw)
            assertNull(value.weeklyProgram)
        }
        val raw = "not an opening hours expression"
        assertEquals(PlaceOpeningState.UNKNOWN, result(raw).state)
        assertEquals(raw, result(raw).raw)
    }

    @Test fun openingIsInclusiveAndClosingIsExclusiveDownToTheSecond() {
        val raw = "Mo 09:00-12:00"
        expect(raw, "2026-11-09T08:59:59Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-09T09:00:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-09T11:59:59Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-09T12:00:00Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-10T10:00:00Z", PlaceOpeningState.CLOSED)
    }

    @Test fun multipleIntervalsDoNotOpenTheLunchGap() {
        val raw = "Mo 09:00-12:00,13:30-18:00 open"
        expect(raw, "2026-11-09T11:00:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-09T12:00:00Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-09T13:30:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-09T18:00:00Z", PlaceOpeningState.CLOSED)
    }

    @Test fun weekdayListsAndWrappedRangesUseTheSelectedDaysOnly() {
        val raw = "Fr-Mo,We 09:00-10:00"
        expect(raw, "2026-11-09T09:30:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-10T09:30:00Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-11T09:30:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-12T09:30:00Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-15T09:30:00Z", PlaceOpeningState.OPEN)
    }

    @Test fun standaloneOvernightSpanCarriesUntilItsExclusiveNextDayEnd() {
        val raw = "Sa 16:00-03:00"
        expect(raw, "2026-11-14T15:59:00Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-14T16:00:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-15T01:00:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-15T03:00:00Z", PlaceOpeningState.CLOSED)
    }

    @Test fun sundayOvernightCarryWrapsIntoMonday() {
        val raw = "Su 22:00-02:00"
        expect(raw, "2026-11-09T01:00:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-09T02:00:00Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-15T23:00:00Z", PlaceOpeningState.OPEN)
    }

    @Test fun oneMultiDayRuleDoesNotClearItsOwnPreviousDayCarry() {
        val raw = "Sa-Su 16:00-03:00"
        expect(raw, "2026-11-15T01:00:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-16T01:00:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-17T01:00:00Z", PlaceOpeningState.CLOSED)
    }

    @Test fun laterNormalRuleReplacesTheSelectedCalendarDayRatherThanAddingHours() {
        val raw = "Mo-Sa 09:00-12:00; We 15:00-18:00"
        expect(raw, "2026-11-11T10:00:00Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-11T16:00:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-10T10:00:00Z", PlaceOpeningState.OPEN)
    }

    @Test fun laterSundayNormalRuleRemovesSaturdaySpillFromSundayMorning() {
        val raw = "Sa 16:00-03:00; Su 16:00-23:00"
        expect(raw, "2026-11-15T01:00:00Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-15T16:00:00Z", PlaceOpeningState.OPEN)
        val sunday = result(raw).weeklyProgram!!.single { it.day == DayOfWeek.SUNDAY }
        assertEquals(listOf(PlaceHourInterval(960, 1380)), sunday.intervals)
    }

    @Test fun timedOffAndClosedCutOnlyTheirWindowsRatherThanReplacingTheDay() {
        for (modifier in listOf("off", "closed")) {
            val raw = "Mo 10:00-18:00; Mo 12:00-13:00 $modifier"
            expect(raw, "2026-11-09T11:00:00Z", PlaceOpeningState.OPEN)
            expect(raw, "2026-11-09T12:00:00Z", PlaceOpeningState.CLOSED)
            expect(raw, "2026-11-09T13:00:00Z", PlaceOpeningState.OPEN)
            expect(raw, "2026-11-09T14:00:00Z", PlaceOpeningState.OPEN)
        }
    }

    @Test fun overnightOffCutsBothDaysButDoesNotEraseOtherOpeningHours() {
        val raw = "24/7; Sa 22:00-02:00 off"
        expect(raw, "2026-11-14T21:00:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-14T23:00:00Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-15T01:00:00Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-15T02:00:00Z", PlaceOpeningState.OPEN)
    }

    @Test fun wholeDayOffCutsIncomingCarryAndLeavesOtherDaysAlone() {
        val raw = "Sa 16:00-03:00; Su off"
        expect(raw, "2026-11-14T22:00:00Z", PlaceOpeningState.OPEN)
        expect(raw, "2026-11-15T01:00:00Z", PlaceOpeningState.CLOSED)
        assertEquals(PlaceOpeningState.CLOSED, result("closed").state)
    }

    @Test fun unknownRuleHasNormalReplacementSemanticsAndPartialUnknownUsesRawProgram() {
        val raw = "Mo 09:00-18:00; Mo 12:00-13:00 unknown"
        expect(raw, "2026-11-09T10:00:00Z", PlaceOpeningState.CLOSED)
        expect(raw, "2026-11-09T12:30:00Z", PlaceOpeningState.UNKNOWN)
        expect(raw, "2026-11-09T14:00:00Z", PlaceOpeningState.CLOSED)
        assertNull(result(raw).weeklyProgram)
        assertEquals(raw, result(raw).raw)
        val day = result("Mo unknown").weeklyProgram!!.single { it.day == DayOfWeek.MONDAY }
        assertEquals(PlaceOpeningState.UNKNOWN, day.state)
        assertTrue(day.intervals.isEmpty())
    }

    @Test fun twentyFourSevenProducesSevenLosslessDaysAndMidnightEndClosesAtNextDay() {
        val all = result("24/7")
        assertEquals(PlaceOpeningState.OPEN, all.state)
        assertEquals(DayOfWeek.values().toList(), all.weeklyProgram!!.map { it.day })
        all.weeklyProgram!!.forEach {
            assertEquals(PlaceOpeningState.OPEN, it.state)
            assertEquals(listOf(PlaceHourInterval(0, 1440)), it.intervals)
        }
        expect("Mo 00:00-24:00", "2026-11-09T23:59:59Z", PlaceOpeningState.OPEN)
        expect("Mo 00:00-24:00", "2026-11-10T00:00:00Z", PlaceOpeningState.CLOSED)
    }

    @Test fun anyUnsupportedClauseMakesTheWholeExpressionUnknownWithOriginalText() {
        val clauses = listOf(
            "PH off", "SH off", "2026 Mo 09:00-17:00", "Jan Mo 09:00-17:00",
            "week 02 Mo 09:00-17:00", "Dec 24 off", "easter off", "sunrise-sunset",
            "Mo 09:00+", "Mo 09:00-25:00", "Mo 24:00-02:00", "Mo 09:00-09:00",
            "Mo 00:00-00:00", "Mo 09:00-17:00 \"appointment\"",
        )
        for (clause in clauses) {
            val raw = "  Mo-Su 00:00-24:00; $clause  "
            val value = result(raw)
            assertEquals(raw, PlaceOpeningState.UNKNOWN, value.state)
            assertEquals(raw, value.raw)
            assertNull(raw, value.weeklyProgram)
        }
        for (raw in listOf("Mo 09:00-12:00 || open", "Sa 16:00-03:00, Su 16:00-23:00", "Mo 09:00-12:00;")) {
            assertEquals(raw, PlaceOpeningState.UNKNOWN, result(raw).state)
            assertEquals(raw, result(raw).raw)
            assertNull(raw, result(raw).weeklyProgram)
        }
    }

    @Test fun suppliedZoneChangesLocalWeekdayAndMinuteWithoutChangingTheInstant() {
        val instant = "2026-11-09T08:30:00Z"
        expect("Mo 09:00-10:00", instant, PlaceOpeningState.CLOSED, ZoneOffset.UTC)
        expect("Mo 09:00-10:00", instant, PlaceOpeningState.OPEN, ZoneId.of("Europe/Berlin"))
        expect("Tu 00:00-01:00", "2026-11-09T23:30:00Z", PlaceOpeningState.OPEN, ZoneId.of("Europe/Berlin"))
    }

    @Test fun springGapUsesActualInstantToLocalTimeInsteadOfInventingMissingMinutes() {
        val berlin = ZoneId.of("Europe/Berlin")
        expect("Su 02:00-03:00", "2026-03-29T00:59:00Z", PlaceOpeningState.CLOSED, berlin)
        expect("Su 02:00-03:00", "2026-03-29T01:00:00Z", PlaceOpeningState.CLOSED, berlin)
        expect("Su 01:30-03:30", "2026-03-29T00:30:00Z", PlaceOpeningState.OPEN, berlin)
        expect("Su 01:30-03:30", "2026-03-29T01:30:00Z", PlaceOpeningState.CLOSED, berlin)
    }

    @Test fun fallOverlapEvaluatesBothOccurrencesOfTheSameLocalMinute() {
        val berlin = ZoneId.of("Europe/Berlin")
        val raw = "Su 02:00-02:30"
        expect(raw, "2026-10-25T00:15:00Z", PlaceOpeningState.OPEN, berlin)
        expect(raw, "2026-10-25T01:15:00Z", PlaceOpeningState.OPEN, berlin)
        expect(raw, "2026-10-25T01:30:00Z", PlaceOpeningState.CLOSED, berlin)
    }
}
