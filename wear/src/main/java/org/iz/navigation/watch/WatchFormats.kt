package org.iz.navigation.watch

import java.util.Locale
import kotlin.math.roundToLong

internal fun pace(secondsPerKm: Double?): String = secondsPerKm?.takeIf { it.isFinite() && it > 0 }?.roundToLong()?.let {
    String.format(Locale.ROOT, "%d:%02d dk/km", it / 60, it % 60)
} ?: "Ölçüm yok"
