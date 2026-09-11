package org.iz.navigation.health

import kotlinx.coroutines.CancellationException
import org.iz.navigation.wearprotocol.WearDailyHealthStatus
import org.iz.navigation.wearprotocol.WearDailyHealthStatus.*

internal data class DailyRange(val startAt: Long, val endAt: Long)
internal data class DailyExercise(val range: DailyRange, val walkingRunning: Boolean)
internal data class DailyHealthReading(
    val steps: Long? = null, val exerciseMillis: Long? = null, val exerciseMeters: Double? = null,
    val stepsStatus: WearDailyHealthStatus = UNAVAILABLE,
    val exerciseStatus: WearDailyHealthStatus = UNAVAILABLE,
    val distanceStatus: WearDailyHealthStatus = UNAVAILABLE,
)

internal object DailyActivityHealthRules {
    private fun union(ranges: List<DailyRange>): List<DailyRange> {
        val result = mutableListOf<DailyRange>()
        for (range in ranges.filter { it.endAt > it.startAt }.sortedBy { it.startAt }) {
            val last = result.lastOrNull()
            if (last != null && range.startAt <= last.endAt) result[result.lastIndex] = DailyRange(last.startAt, maxOf(last.endAt, range.endAt))
            else result += range
        }
        return result
    }
    fun ranges(selected: List<DailyRange>, excluded: List<DailyRange>, window: DailyRange): List<DailyRange> {
        var result = union(selected.map { DailyRange(maxOf(window.startAt, it.startAt), minOf(window.endAt, it.endAt)) })
        for (other in union(excluded)) result = result.flatMap { range ->
            if (other.endAt <= range.startAt || other.startAt >= range.endAt) listOf(range)
            else listOf(DailyRange(range.startAt, minOf(range.endAt, other.startAt)), DailyRange(maxOf(range.startAt, other.endAt), range.endAt)).filter { it.endAt > it.startAt }
        }
        return result
    }
    fun distanceStatus(values: List<Double?>): WearDailyHealthStatus = when {
        values.isEmpty() || values.all { it == null } -> UNAVAILABLE
        values.any { it == null } -> PARTIAL
        else -> AVAILABLE
    }
}

internal interface DailyHealthReader {
    suspend fun steps(range: DailyRange): Long?
    suspend fun exercises(range: DailyRange): List<DailyExercise>
    suspend fun distance(range: DailyRange): Double?
}

/** Independent permissions and errors never turn missing measurements into zero. */
internal suspend fun readDailyHealth(reader: DailyHealthReader, window: DailyRange, stepsAllowed: Boolean, exerciseAllowed: Boolean, distanceAllowed: Boolean): DailyHealthReading {
    var result = DailyHealthReading(stepsStatus = if (stepsAllowed) UNAVAILABLE else PERMISSION_REQUIRED,
        exerciseStatus = if (exerciseAllowed) UNAVAILABLE else PERMISSION_REQUIRED,
        distanceStatus = if (exerciseAllowed && distanceAllowed) UNAVAILABLE else PERMISSION_REQUIRED)
    if (stepsAllowed) try {
        val steps = reader.steps(window)?.takeIf { it >= 0 }
        result = result.copy(steps = steps, stepsStatus = if (steps != null) AVAILABLE else UNAVAILABLE)
    } catch (e: CancellationException) { throw e }
    catch (_: SecurityException) { result = result.copy(stepsStatus = PERMISSION_REQUIRED) }
    catch (_: Exception) { /* This metric remains unavailable; others may succeed. */ }
    if (!exerciseAllowed) return result
    val exercises = try { reader.exercises(window) }
    catch (e: CancellationException) { throw e }
    catch (_: SecurityException) { return result.copy(exerciseStatus = PERMISSION_REQUIRED, distanceStatus = PERMISSION_REQUIRED) }
    catch (_: Exception) { return result }
    val ranges = DailyActivityHealthRules.ranges(exercises.filter { it.walkingRunning }.map { it.range }, exercises.filterNot { it.walkingRunning }.map { it.range }, window)
    if (ranges.isEmpty()) return if (exercises.isEmpty()) result else result.copy(
        exerciseMillis = 0, exerciseStatus = AVAILABLE,
        exerciseMeters = if (distanceAllowed) 0.0 else null,
        distanceStatus = if (distanceAllowed) AVAILABLE else PERMISSION_REQUIRED)
    result = result.copy(exerciseMillis = ranges.sumOf { it.endAt - it.startAt }, exerciseStatus = AVAILABLE)
    if (!distanceAllowed) return result
    var permissionLost = false
    val values = ranges.map { range ->
        try { reader.distance(range)?.takeIf { it.isFinite() && it >= 0 } }
        catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { permissionLost = true; null }
        catch (_: Exception) { null }
    }
    if (permissionLost) return result.copy(distanceStatus = PERMISSION_REQUIRED)
    return result.copy(exerciseMeters = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum(), distanceStatus = DailyActivityHealthRules.distanceStatus(values))
}
