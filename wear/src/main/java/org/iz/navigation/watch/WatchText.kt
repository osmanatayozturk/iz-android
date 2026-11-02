package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.WearMode
import java.util.Locale

internal fun duration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

internal fun WearMode.label(): String = when (this) {
    WearMode.CAR -> "Araba"
    WearMode.MOTORCYCLE -> "Motosiklet"
    WearMode.BICYCLE -> "Bisiklet"
    WearMode.WALK -> "Yürüyüş"
    WearMode.PASSENGER -> "Yolcu"
    WearMode.RUN -> "Koşu"
}
