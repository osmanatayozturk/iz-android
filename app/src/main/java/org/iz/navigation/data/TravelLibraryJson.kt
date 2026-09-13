package org.iz.navigation.data

import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackSegment
import org.iz.navigation.weather.RoutePreferences
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONArray
import org.json.JSONObject

/** Whitelisted metadata/geometry only. Never serializes a routing response or diary measurement. */
internal object TravelLibraryJson {
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
    fun <T> array(values: List<T>, encode: (T) -> JSONObject) = JSONArray().apply { values.forEach { put(encode(it)) } }

    fun stops(values: List<RouteStop>) = array(values) { obj("label" to it.label, "latitude" to it.coordinate.latitude, "longitude" to it.coordinate.longitude) }
    fun readStops(array: JSONArray): List<RouteStop> {
        require(array.length() in 2..5) { "Kaydedilen rota 2–5 durak içermeli." }
        return List(array.length()) { array.getJSONObject(it).let { value -> RouteStop(value.text("label"), coordinate(value)) } }
    }

    fun plan(value: SavedRoutePlan): JSONObject = TravelLibraryRules.normalizePlan(value).let {
        obj("id" to it.id, "name" to it.name, "stops" to stops(it.stops), "transport" to it.transport.name,
            "preferences" to obj("avoidHighways" to it.preferences.avoidHighways, "avoidTolls" to it.preferences.avoidTolls, "avoidFerries" to it.preferences.avoidFerries),
            "travelSpeedKmh" to it.travelSpeedKmh, "originUsesCurrentLocation" to it.originUsesCurrentLocation, "createdAt" to it.createdAt, "updatedAt" to it.updatedAt)
    }

    fun readPlan(value: JSONObject): SavedRoutePlan {
        val preferences = value.getJSONObject("preferences")
        return TravelLibraryRules.normalizePlan(SavedRoutePlan(value.text("id"), value.text("name"), readStops(value.getJSONArray("stops")),
            Transport.valueOf(value.text("transport")), RoutePreferences(preferences.boolean("avoidHighways"), preferences.boolean("avoidTolls"), preferences.boolean("avoidFerries")),
            if (value.isNull("travelSpeedKmh")) null else value.number("travelSpeedKmh"), value.boolean("originUsesCurrentLocation"), value.integer("createdAt"), value.integer("updatedAt")))
    }

    fun track(value: ImportedTrack): JSONObject = TravelLibraryRules.normalizeTrack(value).let {
        obj("id" to it.id, "name" to it.name, "createdAt" to it.createdAt, "segments" to array(it.segments) { segment ->
            obj("name" to segment.name, "trackName" to segment.trackName, "trackIndex" to segment.trackIndex,
                "points" to array(segment.points) { point -> obj("latitude" to point.latitude, "longitude" to point.longitude) })
        })
    }

    fun readTrack(value: JSONObject): ImportedTrack {
        val array = value.getJSONArray("segments")
        require(array.length() in 1..TravelLibraryRules.MAX_SEGMENTS) { "Geçersiz GPX bölüm sayısı." }
        var total = 0L
        val segments = List(array.length()) { index ->
            val segment = array.getJSONObject(index)
            val points = segment.getJSONArray("points")
            total += points.length()
            require(total <= TravelLibraryRules.MAX_TRACK_POINTS) { "GPX nokta sınırı aşıldı." }
            val trackIndex = segment.integer("trackIndex")
            require(trackIndex in 0..99) { "Geçersiz GPX iz grubu." }
            TrackSegment(segment.text("name"), List(points.length()) { coordinate(points.getJSONObject(it)) }, segment.text("trackName"), trackIndex.toInt())
        }
        return TravelLibraryRules.normalizeTrack(ImportedTrack(value.text("id"), value.text("name"), segments, value.integer("createdAt")))
    }

    fun collection(value: JourneyCollection): JSONObject {
        TravelLibraryRules.validateCollection(value)
        return obj("id" to value.id, "name" to value.name, "createdAt" to value.createdAt, "updatedAt" to value.updatedAt)
    }
    fun readCollection(value: JSONObject) = JourneyCollection(value.text("id"), value.text("name"), value.integer("createdAt"), value.integer("updatedAt"))
        .also(TravelLibraryRules::validateCollection)
    fun membership(value: CollectionMembership) = obj("collectionId" to value.collectionId, "journeyId" to value.journeyId, "sortOrder" to value.sortOrder)
    fun readMembership(value: JSONObject) = CollectionMembership(value.text("collectionId"), value.text("journeyId"), value.integer("sortOrder"))

    private fun coordinate(value: JSONObject) = WeatherCoordinate(value.number("latitude"), value.number("longitude"))
    private fun JSONObject.text(key: String) = get(key).also { require(it is String) { "Geçersiz metin: $key" } } as String
    private fun JSONObject.boolean(key: String) = get(key).also { require(it is Boolean) { "Geçersiz seçim: $key" } } as Boolean
    private fun JSONObject.number(key: String): Double {
        val value = get(key)
        require(value is Number && value.toDouble().isFinite()) { "Geçersiz sayı: $key" }
        return value.toDouble()
    }
    private fun JSONObject.integer(key: String): Long {
        val value = get(key)
        require(value is Byte || value is Short || value is Int || value is Long) { "Geçersiz tamsayı: $key" }
        return (value as Number).toLong()
    }
}
