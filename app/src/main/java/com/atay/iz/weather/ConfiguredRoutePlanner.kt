package com.atay.iz.weather

import android.content.Context
import com.atay.iz.data.Transport
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
                override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?) =
                    delegate.plan(stops, departureAt, transport, travelSpeedKmh ?: settings.travelSpeedKmh)
            }
        },
        trafficPlanner = { key, ensureAuthorized -> TomTomRoutePlanner(key, ensureAuthorized) },
    )

    override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?): PlannedRoute = withContext(Dispatchers.IO) {
        require(stops.size in 2..6)
        routeTravelSpeedKmh(transport, travelSpeedKmh)
        if (transport !in TRAFFIC_MODES) return@withContext fallback(transport).plan(stops, departureAt, transport, travelSpeedKmh)
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
                val route = trafficPlanner(snapshot.apiKey!!, ensureAuthorized).plan(stops, departureAt, transport, travelSpeedKmh)
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
        val route = fallback(transport).plan(stops, departureAt, transport, travelSpeedKmh)
        coroutineContext.ensureActive()
        ensureCurrent()
        route.copy(provider = RouteProvider.VALHALLA, traffic = null,
            trafficUnavailableReason = reason ?: "TomTom trafik verisine ulaşılamadı veya kullanım sınırı doldu; trafiksiz rota.")
    }

    private companion object { val TRAFFIC_MODES = setOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE) }
}
