package org.iz.navigation.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.AirlineSeatReclineNormal
import androidx.compose.material.icons.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.iz.navigation.data.Transport

// Display order is independent of persisted names and the companion protocol's wire codes.
internal val transportDisplayOrder = listOf(
    Transport.CAR, Transport.MOTORCYCLE, Transport.BICYCLE, Transport.WALK, Transport.RUN, Transport.PASSENGER,
)

fun Transport.label(): String = when (this) {
    Transport.CAR -> "Araba"
    Transport.MOTORCYCLE -> "Motosiklet"
    Transport.BICYCLE -> "Bisiklet"
    Transport.WALK -> "Yürüyüş"
    Transport.RUN -> "Koşu"
    Transport.PASSENGER -> "Yolcu"
    Transport.UNKNOWN -> "Tür seçilmedi"
}

fun Transport.icon(): ImageVector = when (this) {
    Transport.CAR -> Icons.Outlined.DirectionsCar
    Transport.MOTORCYCLE -> Icons.Outlined.TwoWheeler
    Transport.BICYCLE -> Icons.Outlined.DirectionsBike
    Transport.WALK -> Icons.AutoMirrored.Outlined.DirectionsWalk
    Transport.RUN -> Icons.AutoMirrored.Outlined.DirectionsRun
    Transport.PASSENGER -> Icons.Outlined.AirlineSeatReclineNormal
    Transport.UNKNOWN -> Icons.Outlined.Route
}

internal fun Transport.accentColor(): Color = if (this == Transport.RUN) Color(0xFF8D4F2D) else Forest
internal fun Transport.badgeColor(): Color = if (this == Transport.RUN) Color(0xFFF3E4D6) else Leaf
