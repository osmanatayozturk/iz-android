package org.iz.navigation.navigation

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject
import org.iz.navigation.offline.*
import org.iz.navigation.weather.*

/** Small disposable metadata, bound to exact route and corridor geometry. No Android/native effects. */
internal class PreparedSelectionStore(private val directory: File) {
    fun read(route: PlannedRoute, fingerprint: String, pack: OfflinePack?): PreparedCorridorSelection? = runCatching {
        require(pack != null && pack.request.routeId == route.id && pack.request.sourceFingerprint == fingerprint)
        val file = File(directory, "selection.json")
        require(file.length() in 1..4096L)
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(1024)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= 4096); output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("schema") == 1 && json.getString("fingerprint") == fingerprint && json.getString("routeId") == route.id)
        val selection = PreparedCorridorSelection(json.getDouble("fromMeters"), json.getDouble("toMeters"), json.getDouble("halfWidthMeters"))
        require(selectedLine(route, selection) == pack.request.line && selection.halfWidthMeters * 2 == pack.request.widthMeters)
        selection
    }.getOrNull()
    fun write(route: PlannedRoute, fingerprint: String, selection: PreparedCorridorSelection) {
        val json = JSONObject().put("schema", 1).put("routeId", route.id).put("fingerprint", fingerprint)
            .put("fromMeters", selection.fromMeters).put("toMeters", selection.toMeters).put("halfWidthMeters", selection.halfWidthMeters)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "selection.json.tmp")
        try {
            FileOutputStream(temporary).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temporary.toPath(), File(directory, "selection.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    /** The owner invokes this before both download and resume; management actions do not need it. */
    fun requireForTransfer(state: PreparedRouteState, pack: OfflinePack?) {
        val route = checkNotNull(state.route) { "Hazır rota bulunamadı." }
        val fingerprint = checkNotNull(state.fingerprint) { "Hazır rota kimliği bulunamadı." }
        val selection = checkNotNull(state.selection) { "İndirmeden önce alan seçimini yeniden uygula." }
        check(read(route, fingerprint, pack) == selection) {
            "Kayıtlı alan seçimi doğrulanamadı. İndirmeden önce alanı yeniden uygula."
        }
    }
}

/** Exact route vertices plus interpolated selection endpoints; no straight-line simplification across bends. */
internal fun selectedLine(route: PlannedRoute, selection: PreparedCorridorSelection): List<OfflinePoint> {
    require(listOf(selection.fromMeters, selection.toMeters, selection.halfWidthMeters).all { it.isFinite() })
    require(selection.fromMeters >= 0 && selection.toMeters > selection.fromMeters && selection.halfWidthMeters in 50.0..1000.0)
    val result = mutableListOf<OfflinePoint>(); var distance = 0.0
    fun add(point: WeatherCoordinate) { val p = OfflinePoint(point.latitude, point.longitude); if (result.lastOrNull() != p) result += p }
    for ((a, b) in route.vertices.zipWithNext()) {
        val length = WeatherEngine.distanceMeters(a.coordinate, b.coordinate)
        val end = distance + length
        if (length > 0 && end >= selection.fromMeters && distance <= selection.toMeters) {
            fun at(offset: Double): WeatherCoordinate {
                val f = ((offset - distance) / length).coerceIn(0.0, 1.0)
                val delta = ((b.coordinate.longitude - a.coordinate.longitude + 540) % 360) - 180
                return WeatherCoordinate(a.coordinate.latitude + (b.coordinate.latitude - a.coordinate.latitude) * f,
                    ((a.coordinate.longitude + delta * f + 540) % 360) - 180)
            }
            add(at(selection.fromMeters.coerceAtLeast(distance)))
            add(at(selection.toMeters.coerceAtMost(end)))
        }
        distance = end
        if (distance >= selection.toMeters) break
    }
    require(selection.toMeters <= distance + 0.01 && result.size in 2..512) { "Harita aralığı çok uzun veya rota dışında. Aralığı küçült." }
    return result
}
