package org.iz.navigation.placeinfo

import ch.poole.openinghoursparser.OpeningHoursParser
import ch.poole.openinghoursparser.TokenMgrError
import java.io.ByteArrayInputStream
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

enum class PlaceOpeningState { OPEN, CLOSED, UNKNOWN, MISSING }

/** Calendar-day programs may end at 24:00; end <= start represents a next-day end. */
data class PlaceHourInterval(val startMinute: Int, val endMinute: Int) {
    init { require(startMinute in 0..1439 && endMinute in 0..1440) }
}
data class PlaceDayHours(val day: DayOfWeek, val state: PlaceOpeningState, val intervals: List<PlaceHourInterval>)
data class PlaceHoursResult(val state: PlaceOpeningState, val raw: String?, val weeklyProgram: List<PlaceDayHours>?)

/**
 * A deliberately small weekly evaluator, not evidence that a venue follows its OSM hours.
 * Validate the complete expression with the existing parser, then accept only the grammar below.
 * Never convert through the survey form: semicolon replacement also removes incoming night carry.
 * https://wiki.openstreetmap.org/wiki/Key:opening_hours/specification#Explanations
 */
object BasicPlaceOpeningHours {
    private const val MINUTES_PER_DAY = 1440
    private val dayNames = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private const val DAY = "(?:Mo|Tu|We|Th|Fr|Sa|Su)"
    private const val DAY_SELECTION = "$DAY(?:\\s*-\\s*$DAY)?(?:\\s*,\\s*$DAY(?:\\s*-\\s*$DAY)?)*"
    private const val CLOCK = "[0-9]{2}:[0-9]{2}"
    private const val SPANS = "$CLOCK\\s*-\\s*$CLOCK(?:\\s*,\\s*$CLOCK\\s*-\\s*$CLOCK)*"
    private val rulePattern = Regex("(?:($DAY_SELECTION)\\s+)?(.+)")
    private val bodyPattern = Regex("(?:($SPANS)(?:\\s+(open|off|closed|unknown))?|(open|off|closed|unknown))")
    private val spanPattern = Regex("($CLOCK)\\s*-\\s*($CLOCK)")

    private data class Rule(val days: List<Int>, val spans: List<PlaceHourInterval>, val state: PlaceOpeningState)

    fun evaluate(raw: String?, instant: Instant, zone: ZoneId): PlaceHoursResult {
        if (raw.isNullOrBlank()) return PlaceHoursResult(PlaceOpeningState.MISSING, raw, null)
        fun unknown() = PlaceHoursResult(PlaceOpeningState.UNKNOWN, raw, null)
        if (!syntaxValid(raw)) return unknown()
        // Parse every clause before applying any: a special clause must never disappear silently.
        val rules = raw.trim().split(';').map { parseRule(it.trim()) ?: return unknown() }
        val week = Array(7) { Array(MINUTES_PER_DAY) { PlaceOpeningState.CLOSED } }
        for (rule in rules) {
            if (rule.state != PlaceOpeningState.CLOSED) {
                // Clear the entire selection first; clearing and painting per day loses this rule's own carry.
                rule.days.forEach { week[it].fill(PlaceOpeningState.CLOSED) }
            }
            for (day in rule.days) for (span in rule.spans) {
                if (span.endMinute > span.startMinute) {
                    week[day].fill(rule.state, span.startMinute, span.endMinute)
                } else {
                    week[day].fill(rule.state, span.startMinute, MINUTES_PER_DAY)
                    week[(day + 1) % 7].fill(rule.state, 0, span.endMinute)
                }
            }
        }
        val local = try { instant.atZone(zone) } catch (_: DateTimeException) { return unknown() }
        val state = week[local.dayOfWeek.value - 1][local.hour * 60 + local.minute]
        return PlaceHoursResult(state, raw, weeklyProgram(week))
    }

    private fun syntaxValid(raw: String): Boolean {
        // OSM tag/editor bound also limits parser work; retain the original text on every rejection.
        if (raw.codePointCount(0, raw.length) > 255 ||
            raw.any { it.code == 127 || it.code < 32 && it !in listOf('\t', '\r', '\n') }) return false
        return try {
            OpeningHoursParser(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
                .rules(true, false).isNotEmpty()
        } catch (_: Exception) { false } catch (_: TokenMgrError) { false }
    }

    private fun parseRule(raw: String): Rule? {
        if (raw == "24/7") return Rule((0..6).toList(), listOf(PlaceHourInterval(0, 1440)), PlaceOpeningState.OPEN)
        val match = rulePattern.matchEntire(raw) ?: return null
        val days = match.groupValues[1].takeIf { it.isNotEmpty() }?.let(::selectedDays) ?: (0..6).toList()
        val body = bodyPattern.matchEntire(match.groupValues[2]) ?: return null
        val modifier = body.groupValues[3].ifEmpty { body.groupValues[2] }
        val state = when (modifier) {
            "", "open" -> PlaceOpeningState.OPEN
            "off", "closed" -> PlaceOpeningState.CLOSED
            "unknown" -> PlaceOpeningState.UNKNOWN
            else -> return null
        }
        val spans = if (body.groupValues[1].isEmpty()) listOf(PlaceHourInterval(0, 1440)) else {
            body.groupValues[1].split(',').map { value ->
                val span = spanPattern.matchEntire(value.trim()) ?: return null
                val start = minute(span.groupValues[1], allowEndOfDay = false) ?: return null
                val end = minute(span.groupValues[2], allowEndOfDay = true) ?: return null
                // 00:00-24:00 is explicit; other equal clocks are ambiguous in this small evaluator.
                if (start == end) return null
                PlaceHourInterval(start, end)
            }
        }
        return Rule(days, spans, state)
    }

    private fun selectedDays(raw: String): List<Int> {
        val selected = linkedSetOf<Int>()
        for (part in raw.split(',')) {
            val ends = part.split('-').map { dayNames.indexOf(it.trim()) }
            val first = ends.first()
            val count = if (ends.size == 1) 1 else (ends.last() - first + 7) % 7 + 1
            repeat(count) { selected += (first + it) % 7 }
        }
        return selected.toList()
    }

    private fun minute(raw: String, allowEndOfDay: Boolean): Int? {
        val hour = raw.substring(0, 2).toInt()
        val minute = raw.substring(3, 5).toInt()
        if (hour in 0..23 && minute in 0..59) return hour * 60 + minute
        return 1440.takeIf { allowEndOfDay && hour == 24 && minute == 0 }
    }

    private fun weeklyProgram(week: Array<Array<PlaceOpeningState>>): List<PlaceDayHours>? =
        week.mapIndexed { index, day ->
            if (day.any { it == PlaceOpeningState.UNKNOWN }) {
                if (!day.all { it == PlaceOpeningState.UNKNOWN }) return null
                PlaceDayHours(DayOfWeek.of(index + 1), PlaceOpeningState.UNKNOWN, emptyList())
            } else {
                val spans = mutableListOf<PlaceHourInterval>()
                var minute = 0
                while (minute < MINUTES_PER_DAY) {
                    if (day[minute] != PlaceOpeningState.OPEN) { minute++; continue }
                    val start = minute
                    while (minute < MINUTES_PER_DAY && day[minute] == PlaceOpeningState.OPEN) minute++
                    spans += PlaceHourInterval(start, minute)
                }
                PlaceDayHours(DayOfWeek.of(index + 1),
                    if (spans.isEmpty()) PlaceOpeningState.CLOSED else PlaceOpeningState.OPEN, spans)
            }
        }
}
