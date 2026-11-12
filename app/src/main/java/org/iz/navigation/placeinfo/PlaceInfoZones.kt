package org.iz.navigation.placeinfo

import java.time.ZoneId
import java.time.DateTimeException

enum class PlaceZoneSource { OVERRIDE, OSM, DEVICE }
data class EffectivePlaceZone(val zone: ZoneId, val source: PlaceZoneSource)

interface PlaceZoneOverrides {
    fun read(key: PlaceInfoKey): String?
    /** Null resets the override; persistence must validate non-null values with ZoneId.of. */
    fun write(key: PlaceInfoKey, zoneId: String?)
}

/** Explicit local preference, then tagged OSM zone, then the supplied device zone; never geolocation. */
object PlaceInfoZones {
    fun resolve(overrideId: String?, osmId: String?, deviceZone: ZoneId): EffectivePlaceZone {
        validZone(overrideId)?.let { return EffectivePlaceZone(it, PlaceZoneSource.OVERRIDE) }
        validZone(osmId)?.let { return EffectivePlaceZone(it, PlaceZoneSource.OSM) }
        return EffectivePlaceZone(deviceZone, PlaceZoneSource.DEVICE)
    }

    private fun validZone(id: String?): ZoneId? {
        if (id.isNullOrBlank() || id.length > 255) return null
        return try { ZoneId.of(id) } catch (_: DateTimeException) { null }
    }
}
