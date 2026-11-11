package org.iz.navigation.ui

import androidx.compose.runtime.saveable.Saver
import org.iz.navigation.car.SharedMapInput
import org.iz.navigation.car.SharedMapStop
import org.iz.navigation.car.SharedMapLinks
import org.iz.navigation.weather.RouteStopLimits
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONArray
import org.json.JSONObject

/** Structural UI restoration only; no jobs, route activation, permissions or credentials. */
internal val SharedMapInputSaver = Saver<SharedMapInput?, String>(
    save = { value -> value?.let(::encodeSharedMapInput).orEmpty() }, restore = ::decodeSharedMapInput)

internal fun encodeSharedMapInput(input: SharedMapInput): String {
    fun stop(value: SharedMapStop) = JSONObject().put("name", value.name).put("current", value.currentLocation)
        .put("lat", value.coordinate?.latitude ?: JSONObject.NULL).put("lon", value.coordinate?.longitude ?: JSONObject.NULL)
    return JSONObject().apply {
        when (input) {
            is SharedMapInput.Single -> { put("kind", "single"); put("stop", stop(input.target)); put("url", input.originalUrl) }
            is SharedMapInput.Itinerary -> {
                put("kind", "itinerary"); put("origin", input.origin?.let(::stop) ?: JSONObject.NULL)
                put("via", JSONArray().apply { input.via.forEach { put(stop(it)) } })
                put("stop", stop(input.destination)); put("url", input.originalUrl)
            }
            is SharedMapInput.ShortLink -> { put("kind", "short"); put("url", input.url) }
            is SharedMapInput.Unsupported -> { put("kind", "unsupported"); put("message", input.message); put("url", input.originalUrl) }
        }
    }.toString()
}

internal fun decodeSharedMapInput(raw: String): SharedMapInput? = runCatching {
    require(raw.length <= 64_000)
    val root = JSONObject(org.iz.navigation.offline.OfflineJsonDepth.requireSafe(raw))
    fun stop(value: JSONObject): SharedMapStop {
        val name = value.getString("name")
        require(name.isNotBlank() && name.length <= 300 && name.none { it.code < 32 })
        val coordinate = if (value.isNull("lat") && value.isNull("lon")) null
            else WeatherCoordinate(value.getDouble("lat"), value.getDouble("lon"))
        return SharedMapStop(name, coordinate, value.getBoolean("current"))
    }
    val url = root.optString("url").takeIf { SharedMapLinks.allowedUrl(it) != null }
    when (root.getString("kind")) {
        "single" -> SharedMapInput.Single(stop(root.getJSONObject("stop")), url)
        "itinerary" -> {
            val via = root.getJSONArray("via"); require(via.length() <= RouteStopLimits.MAX_PLANNED_STOPS - 2)
            SharedMapInput.Itinerary(if (root.isNull("origin")) null else stop(root.getJSONObject("origin")),
                List(via.length()) { stop(via.getJSONObject(it)) }, stop(root.getJSONObject("stop")), url)
        }
        "short" -> SharedMapInput.ShortLink(requireNotNull(url))
        "unsupported" -> SharedMapInput.Unsupported(root.getString("message"), url)
        else -> null
    }
}.getOrNull()
