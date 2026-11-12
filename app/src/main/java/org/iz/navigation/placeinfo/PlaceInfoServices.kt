package org.iz.navigation.placeinfo

import android.content.Context
import org.iz.navigation.integration.osm.OsmNotesClient
import java.io.File

/** App-scoped public reader/cache; local zone preferences are independent of cache eviction. */
object PlaceInfoServices {
    const val DEFAULT_ENDPOINT = OsmNotesClient.API_BASE
    @Volatile private var sharedRepository: PlaceInfoRepository? = null
    @Volatile private var sharedOverrides: PlaceZoneOverrides? = null

    fun repository(context: Context): PlaceInfoRepository = sharedRepository ?: synchronized(this) {
        sharedRepository ?: PlaceInfoRepository(PublicPlaceApiClient(),
            PublicPlaceCache(File(context.applicationContext.cacheDir,"public-place-info")))
            .also { sharedRepository = it }
    }

    fun zoneOverrides(context: Context): PlaceZoneOverrides = sharedOverrides ?: synchronized(this) {
        sharedOverrides ?: context.applicationContext.getSharedPreferences("public_place_zone_overrides",Context.MODE_PRIVATE).let { prefs ->
            StoredPlaceZoneOverrides({ key -> runCatching { prefs.getString(key,null) }.getOrNull() }) { key,value ->
                prefs.edit().apply { if(value == null) remove(key) else putString(key,value) }.apply()
            }
        }.also { sharedOverrides = it }
    }
}
