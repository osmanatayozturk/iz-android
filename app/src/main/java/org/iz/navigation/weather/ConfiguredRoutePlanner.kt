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
) : RoutePlanner, RouteModificationPlanner {
    override suspend fun planModified(request: RouteModificationRequest): PlannedRoute = withContext(Dispatchers.IO) {
        val input = request.copy(stops = request.stops.toList(), avoidAreas = request.avoidAreas.toList())
        val snapshot = settings()
        val context = coroutineContext
        fun current() {
            context.ensureActive()
            if (settings().revision != snapshot.revision) throw CancellationException("Rota ayarları değişti.")
        }
        fun authorized() {
            current()
            val now = settings()
            if (!now.enabled || !now.freePlanAcknowledged || now.apiKey.isNullOrBlank())
                throw CancellationException("Trafik yetkisi değişti.")
        }
        suspend fun modified(delegate: RoutePlanner): PlannedRoute {
            current()
            return if (delegate is RouteModificationPlanner) delegate.planModified(input)
            else {
                if (input.avoidAreas.isNotEmpty()) throw RouteServiceException("Bu sağlayıcı seçili bölümü hariç tutmayı desteklemiyor.")
                // Skipping/adding a stop with no exclusions still calculates an actual new route.
                delegate.plan(input.stops, input.departureAt, input.transport, input.travelSpeedKmh, input.preferences)
            }
        }
        fun checked(route: PlannedRoute): PlannedRoute {
            current()
            check(route.transport == input.transport && route.preferences == input.preferences &&
                route.travelSpeedKmh == routeTravelSpeedKmh(input.transport, input.travelSpeedKmh))
            try { requireAvoidedAreas(route, input.avoidAreas) { context.ensureActive() } }
            catch (_: IllegalArgumentException) { throw RouteServiceException("Seçili bölüm yeni rotadan çıkarılamadı. Mevcut rota korundu.") }
            current()
            return route.copy(avoidAreas = input.avoidAreas)
        }
        val reason = when {
            input.transport !in TRAFFIC_MODES -> "Bu ulaşım türünde trafik verisi kullanılmaz; trafiksiz rota."
            snapshot.apiKey.isNullOrBlank() -> "Kişisel TomTom anahtarı eklenmedi; trafiksiz rota."
            !snapshot.enabled -> "Trafik kapalı; trafiksiz rota."
            !snapshot.freePlanAcknowledged -> "Ücretsiz kullanım onayı verilmedi; trafiksiz rota."
            else -> null
        }
        if (reason == null) {
            try {
                val route = modified(trafficPlanner(requireNotNull(snapshot.apiKey), ::authorized))
                authorized()
                val accepted = checked(route)
                authorized()
                return@withContext accepted
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { current() }
        }
        current()
        checked(modified(fallback(input.transport))).copy(provider = RouteProvider.VALHALLA, traffic = null,
            trafficUnavailableReason = reason ?: "TomTom trafik verisine ulaşılamadı; trafiksiz rota.")
    }

    constructor(context: Context) : this(
        settings = TrafficSettingsStore(context.applicationContext)::credentials,
        fallback = { transport ->
            val settings = WeatherSettingsStore(context.applicationContext).read(transport)
            val delegate = ValhallaRoutePlanner(context.applicationContext, settings.routeEndpoint)
            object : RoutePlanner, RouteModificationPlanner {
                override suspend fun planModified(request: RouteModificationRequest) = delegate.planModified(request)
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
        RouteStopLimits.requireEffective(stops.size)
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
internal class VerifiedTomTomRoutePlanner(private val credentials: () -> TrafficCredentials) : RoutePlanner, RouteModificationPlanner {
    override suspend fun planModified(request: RouteModificationRequest): PlannedRoute {
        val snapshot = credentials()
        val authorize = {
            val current = credentials()
            if (current.revision != snapshot.revision || !current.enabled || !current.freePlanAcknowledged || current.apiKey.isNullOrBlank())
                throw CancellationException("Trafik yetkisi değişti.")
        }
        authorize()
        val result = TomTomRoutePlanner(requireNotNull(snapshot.apiKey), authorize).planModified(request)
        authorize()
        return result
    }
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
