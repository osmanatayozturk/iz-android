package org.iz.navigation.weather

import android.content.Context
import org.iz.navigation.data.Transport
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ConfiguredRoutePlanner internal constructor(
    private val settings: () -> TrafficCredentials,
    private val fallback: (Transport) -> RoutePlanner,
    private val trafficPlanner: (String, () -> Unit) -> RoutePlanner,
) : RoutePlanner {
    constructor(context: Context) : this(
        settings = TrafficSettingsStore(context.applicationContext)::credentials,
        fallback = { transport ->
            val settings = WeatherSettingsStore(context.applicationContext).read(transport)
            val delegate = ValhallaRoutePlanner(context.applicationContext, settings.routeEndpoint)
            object : RoutePlanner {
                override suspend fun alternatives(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                    travelSpeedKmh: Double?, preferences: RoutePreferences) =
                    delegate.alternatives(stops, departureAt, transport, travelSpeedKmh, preferences)
                override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) =
                    delegate.plan(stops, departureAt, transport, travelSpeedKmh, preferences)
            }
        },
        trafficPlanner = { key, ensureAuthorized -> TomTomRoutePlanner(key, ensureAuthorized) },
    )

    override suspend fun alternatives(stops: List<RouteStop>, departureAt: Long, transport: Transport,
        travelSpeedKmh: Double?, preferences: RoutePreferences): RouteAlternatives {
        val snapshot = settings()
        val authorize = {
            val current = settings()
            if (current.revision != snapshot.revision) throw CancellationException("Rota ayarları değişti.")
            check(current.enabled && current.freePlanAcknowledged && !current.apiKey.isNullOrBlank())
        }
        if (transport in TRAFFIC_MODES && snapshot.enabled && snapshot.freePlanAcknowledged && !snapshot.apiKey.isNullOrBlank()) {
            try {
                val result = trafficPlanner(snapshot.apiKey!!, authorize).alternatives(stops, departureAt, transport, travelSpeedKmh, preferences)
                authorize()
                return result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { authorize() }
        }
        val result = fallback(transport).alternatives(stops, departureAt, transport, travelSpeedKmh, preferences)
        if (settings().revision != snapshot.revision) throw CancellationException("Rota ayarları değişti.")
        return result.copy(routes = result.routes.map { it.copy(trafficUnavailableReason = "Trafiksiz Valhalla rotası.") })
    }

    override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute = withContext(Dispatchers.IO) {
        require(stops.size in 2..6)
        routeTravelSpeedKmh(transport, travelSpeedKmh)
        if (transport !in TRAFFIC_MODES) return@withContext fallback(transport).plan(stops, departureAt, transport, travelSpeedKmh, preferences)
            .copy(trafficUnavailableReason = "Bu ulaşım türünde trafik verisi kullanılmaz; trafiksiz rota.")
        val snapshot = settings()
        fun ensureCurrent() {
            if (settings().revision != snapshot.revision) throw CancellationException("Trafik ayarları değişti; eski yanıt iptal edildi.")
        }
        val reason = when {
            snapshot.apiKey.isNullOrBlank() -> "Kişisel TomTom anahtarı eklenmedi; trafiksiz rota."
            !snapshot.freePlanAcknowledged -> "Ücretsiz kullanım onayı verilmedi; trafiksiz rota."
            !snapshot.enabled -> "Trafik kapalı; trafiksiz rota."
            else -> null
        }
        if (reason == null) {
            try {
                val ensureAuthorized = {
                    val current = settings()
                    if (current.revision != snapshot.revision || !current.enabled || !current.freePlanAcknowledged || current.apiKey.isNullOrBlank()) {
                        throw CancellationException("Trafik yetkisi değişti; bekleyen istek iptal edildi.")
                    }
                }
                val route = trafficPlanner(snapshot.apiKey!!, ensureAuthorized).plan(stops, departureAt, transport, travelSpeedKmh, preferences)
                coroutineContext.ensureActive()
                ensureCurrent()
                return@withContext route
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                ensureCurrent()
            }
        }
        ensureCurrent()
        val route = fallback(transport).plan(stops, departureAt, transport, travelSpeedKmh, preferences)
        coroutineContext.ensureActive()
        ensureCurrent()
        route.copy(provider = RouteProvider.VALHALLA, traffic = null,
            trafficUnavailableReason = reason ?: "TomTom trafik verisine ulaşılamadı veya kullanım sınırı doldu; trafiksiz rota.")
    }

    private companion object { val TRAFFIC_MODES = setOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE) }
}

/** Explicit verification never silently substitutes another provider. */
internal class VerifiedTomTomRoutePlanner(private val credentials: () -> TrafficCredentials) : RoutePlanner {
    constructor(context: Context) : this(TrafficSettingsStore(context.applicationContext)::credentials)

    override suspend fun alternatives(stops: List<RouteStop>, departureAt: Long, transport: Transport,
        travelSpeedKmh: Double?, preferences: RoutePreferences): RouteAlternatives {
        val snapshot = credentials()
        val authorize = {
            val current = credentials()
            if (current.revision != snapshot.revision) throw CancellationException("Trafik ayarları değişti.")
            check(current.enabled && current.freePlanAcknowledged && !current.apiKey.isNullOrBlank())
        }
        authorize()
        return TomTomRoutePlanner(requireNotNull(snapshot.apiKey), authorize).alternatives(stops, departureAt, transport, travelSpeedKmh, preferences)
    }

    override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
        val snapshot = credentials()
        val authorize = {
            val current = credentials()
            if (current.revision != snapshot.revision) throw CancellationException("Trafik ayarları değişti.")
            check(current.enabled && current.freePlanAcknowledged && !current.apiKey.isNullOrBlank()) {
                "TomTom trafik hesabı etkin değil. Trafiksiz devam etmeyi seçebilirsin."
            }
        }
        authorize()
        return TomTomRoutePlanner(requireNotNull(snapshot.apiKey), authorize).plan(stops, departureAt, transport, travelSpeedKmh, preferences)
    }
}
