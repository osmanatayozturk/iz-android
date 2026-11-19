package org.iz.navigation.ui

import kotlin.math.abs

internal const val LiveLeanLifetimeMillis = 1_500L
private const val LiveLeanMaxDegrees = 65.0

internal fun validLiveLeanDegrees(reading: LiveLeanReading?, sessionId: String, nowElapsedMillis: Long): Double? =
    reading?.takeIf { it.sessionId == sessionId && it.measuredElapsedMillis in 1L..nowElapsedMillis &&
        nowElapsedMillis - it.measuredElapsedMillis < LiveLeanLifetimeMillis
    }?.signedDegrees?.takeIf { it.isFinite() && abs(it) <= LiveLeanMaxDegrees }
