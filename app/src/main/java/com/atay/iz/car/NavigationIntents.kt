package com.atay.iz.car

import android.content.Intent
import com.atay.iz.weather.WeatherCoordinate
import java.net.URLDecoder

data class NavigationTarget(val coordinate: WeatherCoordinate?, val query: String)

/** Parse only standard geo/navigation links. Parsing never starts recording or network work. */
object NavigationIntents {
    fun parse(intent: Intent): NavigationTarget? = parse(intent.dataString)

    fun parse(raw: String?): NavigationTarget? = runCatching {
        if (raw == null || raw.length > 2_048 || raw.any { it.code < 32 }) return null
        val scheme = raw.substringBefore(':').lowercase()
        if (scheme !in setOf("geo", "google.navigation")) return null
        val body = raw.substringAfter(':')
        val parameterText = if (scheme == "google.navigation") body.removePrefix("?") else body.substringAfter('?', "")
        val params = parameterText.split('&').mapNotNull {
            if ('=' !in it) null else it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }.toMap()
        if (params["q"]?.any { it.code < 32 } == true) return null
        val q = params["q"]?.trim()
        if (!q.isNullOrEmpty()) {
            if (q.length > 200 || q.any { it.code < 32 }) return null
            val coordinateText = q.substringBefore('(').trim()
            val pair = coordinateText.split(',')
            if (pair.size == 2 && pair.all { it.trim().toDoubleOrNull() != null }) {
                val coordinate = WeatherCoordinate(pair[0].trim().toDouble(), pair[1].trim().toDouble())
                val label = q.substringAfter('(', "").removeSuffix(")").trim().ifEmpty { coordinateText }
                return NavigationTarget(coordinate, label)
            }
            return NavigationTarget(null, q)
        }
        if (scheme != "geo") return null
        val pair = body.substringBefore('?').substringBefore(';').split(',')
        if (pair.size != 2) return null
        val coordinate = WeatherCoordinate(pair[0].toDouble(), pair[1].toDouble())
        NavigationTarget(coordinate, "${pair[0]}, ${pair[1]}")
    }.getOrNull()
}
