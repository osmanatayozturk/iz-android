package org.iz.navigation.integration

import org.junit.Assert.*
import org.junit.Test

class SurveyOpeningHoursTest {
    @Test fun pinnedParserAcceptsWeeklyMultipleOvernight24hUnknownAndAdvancedRules() {
        for (value in listOf("Mo-Fr 09:00-17:00", "Mo-Fr 09:00-12:00,13:00-17:00", "Fr-Sa 22:00-02:00", "24/7",
            "Mo 09:00-17:00; Tu-Su unknown", "Mo-Fr 09:00-17:00; PH off", "Apr-Sep Mo-Fr 09:00-17:00; Oct-Mar off")) {
            assertTrue(value, SurveyOpeningHours.isValid(value))
        }
        for (value in listOf("", "not a rule", "Mo 29:99-50:99", "Mo 09:00-")) assertFalse(value, SurveyOpeningHours.isValid(value))
    }

    @Test fun formRoundTripPreservesOriginalAndEditsDoNotDiscardUntouchedDays() {
        val original = "Mo-Fr 09:00-12:00,13:00-17:00; Sa 22:00-02:00; Su off"
        val week = requireNotNull(SurveyOpeningHours.toWeek(original))
        assertEquals(original, week.expression())
        val edited = week.editDay(0, SurveyDayHours(SurveyDayState.OPEN, listOf(SurveyHoursInterval("10:00", "18:00"))))
        assertTrue(edited.expression().startsWith(original))
        assertTrue(edited.expression().endsWith("Mo 10:00-18:00"))
        assertEquals(week.days[5], edited.days[5])
        assertTrue(SurveyOpeningHours.isValid(edited.expression()))
        assertEquals("24/7", requireNotNull(SurveyOpeningHours.toWeek("24/7")).expression())
    }

    @Test fun unknownDaysNeverBecomeOffAndComplexRulesStayAdvanced() {
        val days = MutableList(7) { SurveyDayHours(SurveyDayState.UNKNOWN) }
        days[0] = SurveyDayHours(SurveyDayState.OPEN, listOf(SurveyHoursInterval("09:00", "12:00"), SurveyHoursInterval("13:00", "17:00")))
        val expression = SurveyWeek(days).expression()
        assertTrue(SurveyOpeningHours.isValid(expression)); assertTrue(SurveyOpeningHours.isPartial(expression))
        assertFalse(expression.contains("off"))
        for (value in listOf("Mo-Fr 09:00-17:00; PH off", "Apr-Sep Mo-Fr 09:00-17:00", "Mo-Fr 09:00-17:00 \"appointment\""))
            assertNull(value, SurveyOpeningHours.toWeek(value))
        assertFalse(SurveyOpeningHours.isPartial("24/7 \"unknown is just a comment\""))
    }

    @Test fun completeOsmTagLengthIsAcceptedWithoutSilentTruncation() {
        val value = "24/7 \"" + "ş".repeat(248) + "\""
        assertEquals(255,value.codePointCount(0,value.length))
        assertTrue(SurveyOpeningHours.isValid(value))
        assertFalse(SurveyOpeningHours.isValid(value.replace("ş", "şş")))
    }

    @Test fun incompleteAndInvalidFormFieldsRestoreWithoutRelaxingPublicGrammar() {
        val original = requireNotNull(SurveyOpeningHours.toWeek("Mo-Fr 09:00-17:00; Sa-Su off"))
        val invalid = original.editDay(0,SurveyDayHours(SurveyDayState.OPEN,listOf(SurveyHoursInterval("",""),SurveyHoursInterval("0bad; raw", "17:"))))
        assertFalse(SurveyOpeningHours.isValid(invalid.expression()))
        assertNull(SurveyOpeningHours.toWeek(invalid.expression()))
        val restored = requireNotNull(SurveyOpeningHours.fromDraft(invalid.encodeDraft()))
        assertEquals(invalid.days,restored.days)
        assertEquals(invalid.expression(),restored.expression())
        assertEquals("0bad; raw",restored.days[0].intervals[1].from)
        assertTrue(restored.expression().startsWith("Mo-Fr 09:00-17:00; Sa-Su off"))
    }

    @Test fun formDraftDecoderIsBoundedAndDoesNotReinterpretComplexExpressions() {
        val week=SurveyWeek(List(7) { SurveyDayHours(SurveyDayState.UNKNOWN) })
        val raw=week.encodeDraft()
        assertNull(SurveyOpeningHours.fromDraft(raw.dropLast(1)+",\"extra\":"+"[".repeat(70)+"0"+"]".repeat(70)+"}"))
        assertNull(SurveyOpeningHours.fromDraft(raw.replace("\"original\":null","\"original\":\"Mo-Fr 09:00-17:00; PH off\"")))
        assertFalse(week.editDay(0,SurveyDayHours(SurveyDayState.OPEN,listOf(SurveyHoursInterval("","")))).validInput())
    }

    @Test fun validSingleDigitClockRetainsLosslessFormSupport() {
        val original="Mo 9:00-17:00; Tu-Su unknown"
        assertTrue(SurveyOpeningHours.isValid(original))
        val week=requireNotNull(SurveyOpeningHours.toWeek(original))
        assertTrue(week.validInput())
        assertEquals(original,week.expression())
        assertEquals(original,requireNotNull(SurveyOpeningHours.fromDraft(week.encodeDraft())).expression())
    }
}
