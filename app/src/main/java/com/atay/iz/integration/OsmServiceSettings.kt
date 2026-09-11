package com.atay.iz.integration

import android.content.Context
import java.net.URI

data class OsmServiceEndpoints(
    val tileUrl: String = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    val nominatim: String = "https://nominatim.openstreetmap.org/search",
    val overpass: String = "https://overpass-api.de/api/interpreter",
)

/** Public read services only. OAuth credentials are deliberately stored elsewhere. */
class OsmServiceSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("osm_services", Context.MODE_PRIVATE)

    fun read(): OsmServiceEndpoints {
        val defaults = OsmServiceEndpoints()
        return OsmServiceEndpoints(
            tileUrl = preferences.getString("tiles", null)?.takeIf(::isAllowedOsmTileTemplate) ?: defaults.tileUrl,
            nominatim = preferences.getString("nominatim", null)?.takeIf(::isAllowedOsmEndpoint) ?: defaults.nominatim,
            overpass = preferences.getString("overpass", null)?.takeIf(::isAllowedOsmEndpoint) ?: defaults.overpass,
        )
    }

    fun save(endpoints: OsmServiceEndpoints) {
        val normalized = endpoints.copy(
            tileUrl = endpoints.tileUrl.trim(), nominatim = endpoints.nominatim.trim(), overpass = endpoints.overpass.trim(),
        )
        require(isAllowedOsmTileTemplate(normalized.tileUrl)) { "Harita adresi HTTPS ve {z}/{x}/{y} içermeli." }
        require(isAllowedOsmEndpoint(normalized.nominatim) && isAllowedOsmEndpoint(normalized.overpass)) {
            "Servis adresleri kimlik bilgisi içermeyen geçerli HTTPS adresleri olmalı."
        }
        preferences.edit().putString("tiles", normalized.tileUrl)
            .putString("nominatim", normalized.nominatim).putString("overpass", normalized.overpass).apply()
    }
}

internal fun isAllowedOsmEndpoint(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawFragment == null
}.getOrDefault(false)

internal fun isAllowedOsmTileTemplate(value: String): Boolean =
    listOf("{z}", "{x}", "{y}").all(value::contains) &&
        isAllowedOsmEndpoint(value.replace("{z}", "1").replace("{x}", "1").replace("{y}", "1"))
