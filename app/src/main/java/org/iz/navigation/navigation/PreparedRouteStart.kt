package org.iz.navigation.navigation

import org.iz.navigation.weather.*
import kotlin.math.*

/** Exact prepared geometry only: there is deliberately no planner or network parameter. */
data class PreparedRouteMatch(val ready: Boolean, val message: String, val progress: NavigationProgress? = null)
object PreparedRouteStart {
    private data class Match(val distance: Double, val along: Double)
    fun inspect(route: PlannedRoute, fix: NavigationFix?, now: Long): PreparedRouteMatch {
        fun no(message: String) = PreparedRouteMatch(false, message)
        if (route.provider != RouteProvider.VALHALLA || route.vertices.size < 2 || route.durationSeconds <= 0 ||
            route.maneuvers.isEmpty() || route.maneuvers.any {
                it.beginShapeIndex !in route.vertices.indices || it.endShapeIndex !in route.vertices.indices ||
                    it.beginShapeIndex > it.endShapeIndex || !it.beginElapsedSeconds.isFinite() || !it.endElapsedSeconds.isFinite()
            }) return no("Bu rota çevrimdışı yönlendirme için hazır değil.")
        if (fix == null || fix.recordedAt > now || now - fix.recordedAt > 10_000 ||
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..25f)
            return no("Hazır rota için güncel ve yeterli GPS konumu bekleniyor.")
        val matches = mutableListOf<Match>()
        var along = 0.0
        for (i in 0 until route.vertices.lastIndex) {
            val a = route.vertices[i].coordinate; val b = route.vertices[i + 1].coordinate
            val length = WeatherEngine.distanceMeters(a, b)
            if (length <= 0) continue
            val scale = cos(Math.toRadians((a.latitude + b.latitude) / 2)).coerceAtLeast(0.000001)
            fun lon(value: Double) = ((value - a.longitude + 540) % 360) - 180
            val dx = lon(b.longitude) * scale; val dy = b.latitude - a.latitude
            val bearing = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
            val heading = fix.bearingDegrees?.takeIf { it.isFinite() && it in 0f..360f }
            val moving = fix.speedMps?.let { it.isFinite() && it >= 3f } == true
            if (!moving || heading == null || abs((bearing - heading + 540) % 360 - 180) <= 100) {
                val fraction = ((lon(fix.coordinate.longitude) * scale * dx +
                    (fix.coordinate.latitude - a.latitude) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                val point = WeatherCoordinate(a.latitude + dy * fraction,
                    ((a.longitude + lon(b.longitude) * fraction + 540) % 360) - 180)
                matches += Match(WeatherEngine.distanceMeters(point, fix.coordinate), along + length * fraction)
            }
            along += length
        }
        val closest = matches.minOfOrNull { it.distance } ?: return no("Sürüş yönü hazır rotayla eşleşmiyor.")
        if (closest > max(10.0, fix.accuracyMeters.toDouble()))
            return no("Konum hazır rotanın dışında. Bağlantı rotası için internet gerekiyor.")
        val candidates = matches.filter { it.distance <= closest + max(1.0, fix.accuracyMeters.toDouble()) }
        if (candidates.isNotEmpty() && candidates.maxOf { it.along } - candidates.minOf { it.along } > 75)
            return no("Konum birden fazla rota bölümüyle eşleşiyor. Daha belirgin bir konum bekle.")
        val progress = NavigationEngine(route).update(fix, now) ?: return no("Hazır rota için konum bekleniyor.")
        if (progress.distanceFromRouteMeters > max(10.0, fix.accuracyMeters.toDouble()))
            return no("Konum hazır rotayla eşleşmiyor.")
        return PreparedRouteMatch(true, "Hazır rota izlenebilir. Yeni rota için internet gerekir.", progress)
    }
}