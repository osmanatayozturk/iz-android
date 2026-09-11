package org.iz.navigation.health

import org.iz.navigation.wearprotocol.WearDailySummary
import org.iz.navigation.wearprotocol.WearDailyHealthStatus.*

internal enum class DailyAccessKnowledge { UNKNOWN, DISABLED, UNAVAILABLE, AVAILABLE }
internal data class DailyAccessProjection(val visible: WearDailySummary, val cacheReplacement: WearDailySummary?)

/** An inconclusive provider lookup is not evidence of disconnect or permission revocation. */
internal object DailyActivityAccessPolicy {
    fun project(value: WearDailySummary, knowledge: DailyAccessKnowledge,
        stepsAllowed: Boolean = false, exerciseAllowed: Boolean = false, distanceAllowed: Boolean = false): DailyAccessProjection {
        if (knowledge != DailyAccessKnowledge.AVAILABLE) {
            val hidden = value.copy(samsungSteps = null, samsungExerciseMillis = null, samsungExerciseMeters = null,
                stepsStatus = UNAVAILABLE, exerciseStatus = UNAVAILABLE, distanceStatus = UNAVAILABLE,
                healthCheckedAt = value.healthCheckedAt.takeIf { knowledge == DailyAccessKnowledge.UNKNOWN })
            return DailyAccessProjection(hidden, hidden.takeIf { knowledge != DailyAccessKnowledge.UNKNOWN && it != value })
        }
        val distance = exerciseAllowed && distanceAllowed
        val safe = value.copy(samsungSteps = value.samsungSteps.takeIf { stepsAllowed },
            samsungExerciseMillis = value.samsungExerciseMillis.takeIf { exerciseAllowed },
            samsungExerciseMeters = value.samsungExerciseMeters.takeIf { distance },
            stepsStatus = if (stepsAllowed) value.stepsStatus else PERMISSION_REQUIRED,
            exerciseStatus = if (exerciseAllowed) value.exerciseStatus else PERMISSION_REQUIRED,
            distanceStatus = if (distance) value.distanceStatus else PERMISSION_REQUIRED)
        return DailyAccessProjection(safe, safe.takeIf { it != value })
    }
}
