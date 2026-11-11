package org.iz.navigation.integration

import ch.poole.openinghoursparser.OpeningHoursParser
import ch.poole.openinghoursparser.TokenMgrError
import java.io.ByteArrayInputStream
import org.iz.navigation.offline.OfflineJsonDepth
import org.json.JSONArray
import org.json.JSONObject

/** Syntax only, never evidence that a venue actually follows these hours. */
object SurveyOpeningHours {
    fun isValid(value: String): Boolean {
        if (value.isBlank() || value.codePointCount(0, value.length) > 255 ||
            value.any { it.code == 127 || it.code < 32 && it !in listOf('\t', '\n', '\r') }) return false
        return try {
            OpeningHoursParser(ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))).rules(true, false).isNotEmpty()
        } catch (_: Exception) { false } catch (_: TokenMgrError) { false }
    }

    fun isPartial(value: String): Boolean {
        if (!isValid(value)) return true
        val outsideComments = buildString {
            var quoted = false; var escaped = false
            for (c in value) {
                if (escaped) { escaped = false; continue }
                if (c == '\\' && quoted) { escaped = true; continue }
                if (c == '"') { quoted = !quoted; append(' '); continue }
                if (!quoted) append(c)
            }
        }
        return Regex("\\bunknown\\b|[0-9]{1,2}:[0-9]{2}\\s*\\+", RegexOption.IGNORE_CASE).containsMatchIn(outsideComments)
    }

    fun toWeek(value: String): SurveyWeek? {
        if (!isValid(value)) return null
        if (value == "24/7") return SurveyWeek(List(7) { SurveyDayHours(SurveyDayState.OPEN, listOf(SurveyHoursInterval("00:00", "24:00"))) }, value)
        val days = MutableList(7) { SurveyDayHours(SurveyDayState.CLOSED) }
        for (raw in value.split(';')) {
            val match = simpleRule.matchEntire(raw.trim()) ?: return null
            val selected = parseDays(match.groupValues[1]) ?: return null
            val body = match.groupValues[2]
            val hours = when (body) {
                "off", "closed" -> SurveyDayHours(SurveyDayState.CLOSED)
                "unknown" -> SurveyDayHours(SurveyDayState.UNKNOWN)
                else -> {
                    val intervals = body.split(',').map { span ->
                        val times = interval.matchEntire(span.trim()) ?: return null
                        if (!validSurveyClock(times.groupValues[1]) || !validSurveyClock(times.groupValues[2])) return null
                        SurveyHoursInterval(times.groupValues[1], times.groupValues[2])
                    }
                    SurveyDayHours(SurveyDayState.OPEN, intervals)
                }
            }
            selected.forEach { days[it] = hours }
        }
        return SurveyWeek(days.toList(), value)
    }

    /** Private editor state only. This does not accept an incomplete expression as public OSM syntax. */
    fun fromDraft(json: String): SurveyWeek? = runCatching {
        require(json.length <= 64_000 && json.toByteArray(Charsets.UTF_8).size <= 64_000)
        val root = JSONObject(OfflineJsonDepth.requireSafe(json))
        require(root.get("version") == 1)
        fun hours(value: JSONObject): SurveyDayHours {
            val state = value.get("state"); require(state is String)
            val intervals = value.getJSONArray("intervals"); require(intervals.length() <= 64)
            return SurveyDayHours(SurveyDayState.valueOf(state),(0 until intervals.length()).map { index ->
                val item = intervals.getJSONObject(index)
                val from = item.get("from"); val until = item.get("until")
                require(from is String && until is String && from.length <= 8192 && until.length <= 8192)
                SurveyHoursInterval(from,until)
            })
        }
        val rawDays = root.getJSONArray("days"); require(rawDays.length() == 7)
        val days = (0..6).map { hours(rawDays.getJSONObject(it)) }
        val original = if (root.isNull("original")) null else root.get("original").also { require(it is String) } as String
        val originalWeek = original?.let { requireNotNull(toWeek(it)) }
        val rawEdits = root.getJSONArray("edits"); require(rawEdits.length() <= 7)
        val edits = linkedMapOf<Int,SurveyDayHours>()
        for (index in 0 until rawEdits.length()) {
            val item = rawEdits.getJSONObject(index)
            val day = item.get("day"); require(day is Number && day.toDouble() == day.toInt().toDouble())
            val position = day.toInt(); require(position in 0..6 && !edits.containsKey(position))
            edits[position] = hours(item.getJSONObject("hours"))
            require(edits[position] == days[position])
        }
        if (originalWeek != null) for (day in 0..6) require(days[day] == (edits[day] ?: originalWeek.days[day]))
        SurveyWeek(days,original,edits)
    }.getOrNull()

    private fun parseDays(value: String): List<Int>? {
        val result = mutableListOf<Int>()
        for (part in value.split(',')) {
            val ends = part.split('-')
            if (ends.size !in 1..2) return null
            val start = surveyDays.indexOf(ends[0]); val end = surveyDays.indexOf(ends.last())
            if (start < 0 || end < 0) return null
            var index = start
            do { result += index; if (index == end) break; index = (index + 1) % 7 } while (index != start)
        }
        return result
    }

    private val simpleRule = Regex("((?:Mo|Tu|We|Th|Fr|Sa|Su)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?(?:,(?:Mo|Tu|We|Th|Fr|Sa|Su)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?)*)\\s+(.+)")
    private val interval = Regex("([0-9]{1,2}:[0-9]{2})-([0-9]{1,2}:[0-9]{2})")
}

enum class SurveyDayState { OPEN, CLOSED, UNKNOWN }
data class SurveyHoursInterval(val from: String, val until: String)
data class SurveyDayHours(val state: SurveyDayState, val intervals: List<SurveyHoursInterval> = emptyList()) {
    internal fun expression() = when (state) {
        SurveyDayState.OPEN -> intervals.joinToString(",") { "${it.from}-${it.until}" }
        SurveyDayState.CLOSED -> "off"
        SurveyDayState.UNKNOWN -> "unknown"
    }
}
internal val surveyDays = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
private fun validSurveyClock(value: String): Boolean = value == "24:00" || Regex("(?:[0-9]|[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)

/** Keep original normal rules byte-for-byte; explicit day overrides leave other days untouched. */
data class SurveyWeek(
    val days: List<SurveyDayHours>,
    private val original: String? = null,
    private val edits: Map<Int, SurveyDayHours> = emptyMap(),
) {
    init { require(days.size == 7) }
    fun validInput(): Boolean = days.all { it.state != SurveyDayState.OPEN || it.intervals.isNotEmpty() &&
        it.intervals.all { interval -> validSurveyClock(interval.from) && validSurveyClock(interval.until) } }
    fun encodeDraft(): String {
        fun hours(value: SurveyDayHours) = JSONObject().put("state",value.state.name)
            .put("intervals",JSONArray().apply { value.intervals.forEach { put(JSONObject().put("from",it.from).put("until",it.until)) } })
        return JSONObject().put("version",1).put("original",original ?: JSONObject.NULL)
            .put("days",JSONArray().apply { days.forEach { put(hours(it)) } })
            .put("edits",JSONArray().apply { edits.toSortedMap().forEach { (day,value) -> put(JSONObject().put("day",day).put("hours",hours(value))) } })
            .toString().also { require(it.length <= 64_000 && SurveyOpeningHours.fromDraft(it) != null) }
    }
    fun expression(): String {
        if (original != null) return original + edits.entries.sortedBy { it.key }
            .joinToString("") { (day, hours) -> "; ${surveyDays[day]} ${hours.expression()}" }
        if (days.all { it.state == SurveyDayState.OPEN && it.intervals == listOf(SurveyHoursInterval("00:00", "24:00")) }) return "24/7"
        return days.mapIndexed { day, hours -> "${surveyDays[day]} ${hours.expression()}" }.joinToString("; ")
    }
    fun editDay(index: Int, value: SurveyDayHours): SurveyWeek {
        require(index in 0..6)
        return copy(days = days.toMutableList().apply { set(index, value) }.toList(), edits = edits + (index to value))
    }
}
