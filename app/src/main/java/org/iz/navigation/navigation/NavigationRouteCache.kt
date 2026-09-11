package org.iz.navigation.navigation

import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** A single loaded route, never an automatically resumed session. Store under noBackupFilesDir. */
class NavigationRouteCache(private val directory: File) {
    private val file get() = File(directory, "route.json")
    private val temporary get() = File(directory, "route.json.tmp")

    @Synchronized fun read(): PlannedRoute? = try {
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) null else {
            val bytes = file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getInt("version") == 1)
            val stops = json.getJSONArray("stops").bounded(6).map { item ->
                RouteStop(item.getString("label"), item.coordinate())
            }
            val vertices = json.getJSONArray("vertices").bounded(100_000).map { item ->
                RouteVertex(item.coordinate(), item.getDouble("elapsed"))
            }
            val times = json.optJSONArray("stopTimes") ?: JSONArray()
            require(times.length() <= 6)
            val maneuvers = (json.optJSONArray("maneuvers") ?: JSONArray()).bounded(10_000).map { item ->
                val streets = item.optJSONArray("streets") ?: JSONArray()
                require(streets.length() <= 30)
                RouteManeuver(item.getInt("type"), item.optString("instruction", ""), item.optString("verbal", ""),
                    (0 until streets.length()).map { streets.getString(it) }, item.getInt("begin"), item.getInt("end"),
                    item.getDouble("beginTime"), item.getDouble("endTime"),
                    if (item.isNull("exit")) null else item.getInt("exit"))
            }
            PlannedRoute(json.getString("id"), stops, vertices, json.getDouble("distance"), json.getDouble("duration"),
                json.getLong("createdAt"), (0 until times.length()).map { times.getDouble(it) },
                Transport.valueOf(json.optString("transport", Transport.MOTORCYCLE.name)),
                if (json.isNull("speed")) null else json.getDouble("speed"), maneuvers).also(::validate)
        }
    } catch (_: Exception) { null }

    @Synchronized fun write(route: PlannedRoute) {
        if (route.provider == RouteProvider.TOMTOM) {
            clear()
            return
        }
        validate(route)
        val json = JSONObject().put("version", 1).put("id", route.id).put("distance", route.distanceMeters)
            .put("duration", route.durationSeconds).put("createdAt", route.createdAt).put("transport", route.transport.name)
            .put("speed", route.travelSpeedKmh ?: JSONObject.NULL)
            .put("stops", JSONArray().apply { route.stops.forEach { put(it.coordinate.json().put("label", it.label)) } })
            .put("vertices", JSONArray().apply { route.vertices.forEach { put(it.coordinate.json().put("elapsed", it.elapsedSeconds)) } })
            .put("stopTimes", JSONArray(route.stopElapsedSeconds))
            .put("maneuvers", JSONArray().apply {
                route.maneuvers.forEach { m -> put(JSONObject().put("type", m.type).put("instruction", m.instruction)
                    .put("verbal", m.verbalInstruction).put("streets", JSONArray(m.streetNames))
                    .put("begin", m.beginShapeIndex).put("end", m.endShapeIndex).put("beginTime", m.beginElapsedSeconds)
                    .put("endTime", m.endElapsedSeconds).put("exit", m.roundaboutExit ?: JSONObject.NULL)) }
            })
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Rota önbelleği çok büyük." }
        check(directory.isDirectory || directory.mkdirs()) { "Rota önbelleği oluşturulamadı." }
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    @Synchronized fun clear() {
        Files.deleteIfExists(file.toPath())
        Files.deleteIfExists(temporary.toPath())
    }

    private fun validate(route: PlannedRoute) {
        require(route.id.length <= 300 && route.vertices.size <= 100_000 && route.maneuvers.size <= 10_000)
        route.maneuvers.forEach { m ->
            require(m.beginShapeIndex in route.vertices.indices && m.endShapeIndex in m.beginShapeIndex until route.vertices.size)
            require(m.beginElapsedSeconds.isFinite() && m.endElapsedSeconds.isFinite() &&
                m.beginElapsedSeconds >= 0 && m.endElapsedSeconds >= m.beginElapsedSeconds && m.endElapsedSeconds <= route.durationSeconds + .001)
            require(kotlin.math.abs(route.vertices[m.beginShapeIndex].elapsedSeconds - m.beginElapsedSeconds) <= .001 &&
                kotlin.math.abs(route.vertices[m.endShapeIndex].elapsedSeconds - m.endElapsedSeconds) <= .001)
            require(m.instruction.length <= 2000 && m.verbalInstruction.length <= 2000 &&
                m.streetNames.size <= 30 && m.streetNames.all { it.length <= 300 })
            require(m.roundaboutExit == null || m.roundaboutExit in 1..100)
        }
        require(route.maneuvers.zipWithNext().all { (a, b) ->
            a.endShapeIndex <= b.beginShapeIndex && a.endElapsedSeconds <= b.beginElapsedSeconds + .001
        })
    }

    private fun JSONArray.bounded(maximum: Int): List<JSONObject> {
        require(length() <= maximum)
        return (0 until length()).map { getJSONObject(it) }
    }
    private fun JSONObject.coordinate() = WeatherCoordinate(getDouble("lat"), getDouble("lon"))
    private fun WeatherCoordinate.json() = JSONObject().put("lat", latitude).put("lon", longitude)
    companion object { private const val MAX_BYTES = 8_000_000 }
}
