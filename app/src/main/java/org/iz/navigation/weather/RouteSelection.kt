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
    val key = selected.geometryKey()
    val found = alternatives(stops, departureAt, transport, travelSpeedKmh, preferences).routes.firstOrNull { it.geometryKey() == key }
        ?: throw RouteServiceException("Seçilen yol veya başlangıç konumu değişti. Rotayı yeniden hesaplayıp seç; farklı bir yol kendiliğinden başlatılmaz.")
    return found.copy(selectionLocked = true).requireUsablePreferences()
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
