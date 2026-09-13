package org.iz.navigation.weather

import java.security.MessageDigest
import org.iz.navigation.data.Transport
import org.json.JSONObject
import kotlin.math.roundToLong

fun Transport.requiresHighwayProof(preferences: RoutePreferences) =
    preferences.avoidHighways && this in setOf(Transport.WALK, Transport.RUN, Transport.BICYCLE)

/** Provider classification is evidence about its map, never a real-world safety guarantee. */
fun PlannedRoute.requireUsablePreferences(): PlannedRoute {
    if (transport.requiresHighwayProof(preferences) && hasHighway != false) {
        throw RouteServiceException(if (hasHighway == true) "Otoyol içeren rota kullanılamaz. Başlangıç ve durakları değiştirip yeniden hesapla."
            else "Bu servisle otoyolsuz rota doğrulanamadı. Başka bir rota servisi seç veya tercihini gözden geçir.")
    }
    return this
}

/** Geometry identity ignores timestamps and stationary duplicated points, but never a changed path. */
fun PlannedRoute.geometryKey(): String {
    val coordinates = vertices.map { (it.coordinate.latitude * 1e6).roundToLong() to (it.coordinate.longitude * 1e6).roundToLong() }
    val canonical = coordinates.filterIndexed { index, value -> index == 0 || value != coordinates[index - 1] }
        .joinToString(";") { "${it.first},${it.second}" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

data class RouteAlternatives(val routes: List<PlannedRoute>, val message: String? = null) {
    init { require(routes.size in 1..3) }
}

internal fun uniqueAlternatives(routes: List<PlannedRoute>, message: String? = null): RouteAlternatives {
    val unique = routes.distinctBy { it.geometryKey() }.take(3)
    if (unique.isEmpty()) throw RouteServiceException("Geçerli bir rota alternatifi alınamadı.")
    return RouteAlternatives(unique, message ?: if (unique.size == 1) "Bu duraklar ve tercihler için başka bir rota bulunamadı." else null)
}

suspend fun RoutePlanner.revalidateSelection(selected: PlannedRoute, stops: List<RouteStop>, departureAt: Long,
    transport: Transport, travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute {
    require(selected.transport == transport && selected.preferences == preferences) { "Rota tercihleri değişti. Yeniden hesaplayıp seç." }
    if (stops.size == selected.stops.size + 1 && stops.drop(1) == selected.stops) {
        // A current-location approach is a separate leg, not a replacement for the selected path.
        // Keeping the suffix request separate also preserves Valhalla's two-location alternatives.
        val approach = plan(stops.take(2), departureAt, transport, travelSpeedKmh, preferences).requireUsablePreferences()
        val approachDeparture = approach.effectiveDepartureAt ?: departureAt
        val approachMillis = kotlin.math.ceil(approach.durationSeconds * 1000.0).toLong()
        if (approachMillis <= 0L || approachDeparture < 0L || approachDeparture > Long.MAX_VALUE - approachMillis)
            throw RouteServiceException("Başlangıca yaklaşım süresi doğrulanamadı. Rotayı yeniden hesaplayıp seç.")
        val suffix = revalidateSelection(selected, selected.stops, approachDeparture + approachMillis,
            transport, travelSpeedKmh, preferences)
        return joinApproachToSelection(approach, suffix, approachDeparture)
    }
    val key = selected.geometryKey()
    val found = alternatives(stops, departureAt, transport, travelSpeedKmh, preferences).routes.firstOrNull {
        it.transport == transport && it.preferences == preferences && it.stops == stops && it.geometryKey() == key
    }
        ?: throw RouteServiceException("Seçilen yol veya başlangıç konumu değişti. Rotayı yeniden hesaplayıp seç; farklı bir yol kendiliğinden başlatılmaz.")
    return found.copy(selectionLocked = true).requireUsablePreferences()
}

/** Join only a real two-stop approach and the freshly verified selected suffix. */
private fun joinApproachToSelection(approach: PlannedRoute, suffix: PlannedRoute, departureAt: Long): PlannedRoute {
    fun verify(condition: Boolean) {
        if (!condition) throw RouteServiceException("Başlangıca yaklaşım seçilen yola güvenle bağlanamadı. Rotayı yeniden hesaplayıp seç.")
    }
    verify(approach.provider == suffix.provider && approach.transport == suffix.transport &&
        approach.preferences == suffix.preferences && approach.travelSpeedKmh == suffix.travelSpeedKmh)
    verify(approach.stops.size == 2 && approach.stops.last() == suffix.stops.first() && suffix.stops.size <= 5)
    verify(approach.vertices.last().coordinate == suffix.vertices.first().coordinate)
    verify(listOf(approach, suffix).all { it.vertices.size >= 2 && it.durationSeconds > 0 &&
        it.vertices.first().elapsedSeconds == 0.0 && kotlin.math.abs(it.vertices.last().elapsedSeconds - it.durationSeconds) <= .001 })
    verify(approach.vertices.size + suffix.vertices.size - 1 <= 100_000 && approach.maneuvers.size + suffix.maneuvers.size <= 10_000)
    val suffixTimes = suffix.stopElapsedSeconds.ifEmpty {
        verify(suffix.stops.size == 2)
        listOf(0.0, suffix.durationSeconds)
    }
    verify(suffixTimes.first() == 0.0 && kotlin.math.abs(suffixTimes.last() - suffix.durationSeconds) <= .001)
    val indexOffset = approach.vertices.lastIndex
    val timeOffset = approach.durationSeconds
    val traffic = approach.traffic?.let { first -> suffix.traffic?.let { second ->
        RouteTrafficInfo(minOf(first.fetchedAt, second.fetchedAt), first.delaySeconds + second.delaySeconds,
            if (first.noTrafficDurationSeconds != null && second.noTrafficDurationSeconds != null)
                first.noTrafficDurationSeconds + second.noTrafficDurationSeconds else null,
            first.experimental || second.experimental)
    } }
    val id = MessageDigest.getInstance("SHA-256").digest("${approach.id}:${suffix.id}:$departureAt".toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }
    return suffix.copy(
        id = id, stops = approach.stops.take(1) + suffix.stops,
        vertices = approach.vertices + suffix.vertices.drop(1).map { it.copy(elapsedSeconds = it.elapsedSeconds + timeOffset) },
        distanceMeters = approach.distanceMeters + suffix.distanceMeters,
        durationSeconds = timeOffset + suffix.durationSeconds, createdAt = minOf(approach.createdAt, suffix.createdAt),
        stopElapsedSeconds = listOf(0.0) + suffixTimes.map { it + timeOffset },
        maneuvers = approach.maneuvers.filterNot { it.type in 4..6 && it.endShapeIndex == indexOffset } + suffix.maneuvers.map {
            it.copy(beginShapeIndex = it.beginShapeIndex + indexOffset, endShapeIndex = it.endShapeIndex + indexOffset,
                beginElapsedSeconds = it.beginElapsedSeconds + timeOffset, endElapsedSeconds = it.endElapsedSeconds + timeOffset)
        },
        traffic = traffic,
        trafficUnavailableReason = if (suffix.provider == RouteProvider.TOMTOM && traffic == null)
            "Yaklaşımın veya seçilen bölümün trafik verisi eksik; toplam trafik gecikmesi gösterilemiyor."
            else listOfNotNull(approach.trafficUnavailableReason, suffix.trafficUnavailableReason).distinct().joinToString(" ").ifBlank { null },
        speedLimits = approach.speedLimits + suffix.speedLimits.map {
            it.copy(beginShapeIndex = it.beginShapeIndex + indexOffset, endShapeIndex = it.endShapeIndex + indexOffset)
        },
        effectiveDepartureAt = departureAt,
        hasHighway = when { approach.hasHighway == true || suffix.hasHighway == true -> true
            approach.hasHighway == false && suffix.hasHighway == false -> false; else -> null },
        providerWarnings = (approach.providerWarnings + suffix.providerWarnings).distinct(), selectionLocked = true,
    ).requireUsablePreferences()
}

internal fun RoutePreferences.json() = JSONObject().put("avoidHighways", avoidHighways).put("avoidTolls", avoidTolls).put("avoidFerries", avoidFerries)

internal fun routePreferences(json: JSONObject?, defaults: RoutePreferences): RoutePreferences {
    if (json == null) return defaults
    fun choice(name: String, default: Boolean): Boolean = if (!json.has(name)) default else
        (json.get(name) as? Boolean ?: throw IllegalArgumentException("Geçersiz rota tercihi."))
    return RoutePreferences(choice("avoidHighways", defaults.avoidHighways), choice("avoidTolls", defaults.avoidTolls), choice("avoidFerries", defaults.avoidFerries))
}

fun RoutePreferences.matrixUnavailableReason(transport: Transport): String? = when {
    transport !in setOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE) -> "Bu ulaşım türünde durak sırası önerisi kullanılamıyor."
    avoidHighways || avoidFerries -> "Matrix otoyol veya feribot tercihini uygulayamıyor. Bu tercihler açıkken durak sırası önerilmez."
    else -> null
}
