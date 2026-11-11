package org.iz.navigation.weather

import android.content.Context
import org.iz.navigation.data.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class StopOrderProposal(
    val originalStops: List<RouteStop>,
    val orderedStops: List<RouteStop>,
    val originalRoute: PlannedRoute,
    val proposedRoute: PlannedRoute,
    val comparisonDepartureAt: Long,
    val approximateMotorcycle: Boolean,
    val createdAt: Long,
    val credentialRevision: String,
    val preferences: RoutePreferences = originalRoute.preferences,
    val heuristic: Boolean = false,
    val routeSettings: RideWeatherSettings? = null,
) {
    val savedSeconds: Double get() = originalRoute.durationSeconds - proposedRoute.durationSeconds

    fun canApplyTo(stops: List<RouteStop>, transport: Transport, preferences: RoutePreferences,
        revision: String, now: Long, settings: RideWeatherSettings? = null): Boolean =
        originalStops == stops && this.preferences == preferences && credentialRevision == revision &&
            now - createdAt in 0L..120_000L && (routeSettings == null || routeSettings == settings) &&
            orderedStops.firstOrNull() == stops.firstOrNull() && orderedStops.lastOrNull() == stops.lastOrNull() &&
            orderedStops.groupingBy { it }.eachCount() == stops.groupingBy { it }.eachCount() &&
            savedSeconds > 0 && originalRoute.stops == stops && proposedRoute.stops == orderedStops &&
            originalRoute.provider == proposedRoute.provider &&
            originalRoute.transport == transport && proposedRoute.transport == transport &&
            originalRoute.preferences == preferences && proposedRoute.preferences == preferences &&
            originalRoute.avoidAreas == proposedRoute.avoidAreas &&
            originalRoute.travelSpeedKmh == proposedRoute.travelSpeedKmh &&
            (originalRoute.effectiveDepartureAt ?: comparisonDepartureAt) == comparisonDepartureAt &&
            (proposedRoute.effectiveDepartureAt ?: comparisonDepartureAt) == comparisonDepartureAt
}

/** Exact enumeration of at most six directed orders. Missing edges are never free edges. */
internal fun fastestStopOrder(size: Int, costs: Map<Pair<Int, Int>, Double>): List<Int> {
    require(size in 2..5)
    val original = (0 until size).toList()
    fun cost(order: List<Int>): Double = order.zipWithNext().sumOf { edge ->
        costs[edge]?.takeIf { it.isFinite() && it >= 0.0 } ?: Double.POSITIVE_INFINITY
    }
    var best = original
    var bestCost = cost(original)
    fun visit(prefix: List<Int>, remaining: List<Int>) {
        if (remaining.isEmpty()) {
            val order = listOf(0) + prefix + (size - 1)
            val duration = cost(order)
            if (duration < bestCost) { best = order; bestCost = duration }
        } else remaining.forEach { next -> visit(prefix + next, remaining - next) }
    }
    visit(emptyList(), (1 until size - 1).toList())
    return best
}

internal class TomTomMatrixClient(
    private val apiKey: String,
    endpoint: String = "https://api.tomtom.com/routing/matrix/2",
    client: OkHttpClient = defaultWeatherHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val gate: TrafficRequestGate = sharedTrafficRequestGate,
    private val ensureAuthorized: () -> Unit = {},
) {
    private val endpoint = endpoint.toHttpUrl().also {
        require(it.toString() == "https://api.tomtom.com/routing/matrix/2" || it.host in setOf("localhost", "127.0.0.1", "::1"))
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    private val client = singleAttemptHttpClient(client.newBuilder().apply {
        cache(null)
        interceptors().clear()
        networkInterceptors().clear()
        eventListener(okhttp3.EventListener.NONE)
        connectTimeout(10, TimeUnit.SECONDS)
        readTimeout(20, TimeUnit.SECONDS)
        callTimeout(30, TimeUnit.SECONDS)
    }.build())

    suspend fun costs(stops: List<RouteStop>, departureAt: Long, preferences: RoutePreferences = RoutePreferences()): Map<Pair<Int, Int>, Double> = withContext(Dispatchers.IO) {
        require(stops.size in 4..5)
        preferences.matrixUnavailableReason(Transport.CAR)?.let { throw RouteServiceException(it) }
        require(apiKey.isNotBlank() && apiKey.length <= 256)
        try {
            gate.request(clock, ensureAuthorized) {
                check(departureAt > clock()) { "Karşılaştırma saati geçti; yeniden dene." }
                fun locations(values: List<RouteStop>) = JSONArray(values.map { stop ->
                    JSONObject().put("point", JSONObject().put("latitude", stop.coordinate.latitude).put("longitude", stop.coordinate.longitude))
                })
                val body = JSONObject().put("origins", locations(stops.dropLast(1)))
                    .put("destinations", locations(stops.drop(1)))
                    .put("options", JSONObject().put("departAt", Instant.ofEpochMilli(departureAt).toString())
                        .put("traffic", "live").put("routeType", "fastest").put("travelMode", "car")
                        .apply { if (preferences.avoidTolls) put("avoid", JSONArray().put("tollRoads")) })
                val url = endpoint.newBuilder().addQueryParameter("key", apiKey).build()
                val request = weatherRequest(url.toString()).header("Cache-Control", "no-store")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                ensureAuthorized()
                val response = executeBounded(client, request, 128_000)
                if (response.code == 429 || response.code == 503) gate.coolDown(clock(), response.retryAfter)
                if (!response.successful) throw RouteServiceException("Matrix erişimi veya kullanım sınırı nedeniyle öneri alınamadı.")
                coroutineContext.ensureActive()
                ensureAuthorized()
                val data = JSONObject(response.body).getJSONArray("data")
                require(data.length() <= (stops.size - 1) * (stops.size - 1))
                buildMap {
                    for (index in 0 until data.length()) {
                        val cell = data.getJSONObject(index)
                        if (cell.has("detailedError")) continue
                        val origin = cell.getInt("originIndex")
                        val destination = cell.getInt("destinationIndex") + 1
                        if (origin !in 0 until stops.lastIndex || destination !in 1..stops.lastIndex) continue
                        val duration = cell.optJSONObject("routeSummary")?.optDouble("travelTimeInSeconds", Double.NaN) ?: continue
                        if (duration.isFinite() && duration >= 0.0) put(origin to destination, duration)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            coroutineContext.ensureActive()
            // Never retain exception causes containing the query-string API key.
            throw RouteServiceException("Matrix önerisi alınamadı. Mevcut durak sırası korunuyor.")
        }
    }
}

/** Matrix is a proposal aid; full routes validate travel-time improvement for the actual vehicle. */
internal class ConfiguredStopOrderPlanner internal constructor(
    private val credentials: () -> TrafficCredentials,
    private val clock: () -> Long = System::currentTimeMillis,
    private val matrix: suspend (String, () -> Unit, List<RouteStop>, Long, RoutePreferences) -> Map<Pair<Int, Int>, Double> = { key, authorize, stops, at, preferences ->
        TomTomMatrixClient(key, ensureAuthorized = authorize).costs(stops, at, preferences)
    },
    private val route: suspend (String, () -> Unit, List<RouteStop>, Long, Transport, RoutePreferences) -> PlannedRoute = { key, authorize, stops, at, mode, preferences ->
        TomTomRoutePlanner(key, authorize).plan(stops, at, mode, null, preferences)
    },
    private val largeRoute: (suspend (List<RouteStop>, Long, Transport, RoutePreferences, RouteProvider?) -> PlannedRoute)? = null,
    private val largeSettings: (Transport) -> RideWeatherSettings? = { null },
) {
    constructor(context: Context) : this(
        credentials = TrafficSettingsStore(context.applicationContext)::credentials,
        largeRoute = { stops, at, mode, preferences, provider ->
            val settings = WeatherSettingsStore(context.applicationContext).read(mode)
            val planner: RoutePlanner = when (provider) {
                RouteProvider.TOMTOM -> VerifiedTomTomRoutePlanner(context.applicationContext)
                RouteProvider.VALHALLA -> ValhallaRoutePlanner(context.applicationContext, settings.routeEndpoint)
                null -> ConfiguredRoutePlanner(context.applicationContext)
            }
            planner.plan(stops, at, mode, settings.travelSpeedKmh, preferences)
        },
        largeSettings = { mode -> WeatherSettingsStore(context.applicationContext).read(mode) },
    )

    suspend fun propose(stops: List<RouteStop>, departureAt: Long, transport: Transport): StopOrderProposal? =
        propose(stops, departureAt, transport, RoutePreferences.defaults(transport))

    suspend fun propose(stops: List<RouteStop>, departureAt: Long, transport: Transport, preferences: RoutePreferences): StopOrderProposal? {
        RouteStopLimits.requirePlanned(stops.size)
        if (stops.size >= 6) return proposeLarge(stops.toList(), departureAt, transport, preferences)
        preferences.matrixUnavailableReason(transport)?.let { throw RouteServiceException(it) }
        require(stops.size in 4..5)
        require(transport in setOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE))
        val snapshot = credentials()
        val authorize = {
            val current = credentials()
            if (current.revision != snapshot.revision) throw CancellationException("Matrix yetkisi değişti.")
            check(current.matrixEnabled && current.freeAccountVerified && current.freePlanAcknowledged && !current.apiKey.isNullOrBlank()) {
                "Matrix ücretsiz hesap erişimi henüz doğrulanmadı. Trafik ayarlarını kontrol et."
            }
        }
        authorize()
        val key = requireNotNull(snapshot.apiKey)
        // All three calls use one explicit future instant; expose it in the proposal, not as a new user departure.
        val comparisonAt = ((maxOf(departureAt, clock() + 120_000L) + 999L) / 1000L) * 1000L
        val costs = matrix(key, authorize, stops, comparisonAt, preferences)
        val order = fastestStopOrder(stops.size, costs)
        if (order == stops.indices.toList()) return null
        val ordered = order.map(stops::get)
        authorize()
        check(clock() < comparisonAt) { "Karşılaştırma saati geçti. Yeniden dene." }
        val originalRoute = route(key, authorize, stops, comparisonAt, transport, preferences)
        authorize()
        check(clock() < comparisonAt) { "Karşılaştırma saati geçti. Yeniden dene." }
        val proposedRoute = route(key, authorize, ordered, comparisonAt, transport, preferences)
        authorize()
        check(listOf(originalRoute, proposedRoute).all {
            it.provider == RouteProvider.TOMTOM && it.transport == transport && it.preferences == preferences &&
                (it.effectiveDepartureAt ?: comparisonAt) == comparisonAt
        }) { "Rotalar aynı karşılaştırma saatine ait değil." }
        if (proposedRoute.durationSeconds >= originalRoute.durationSeconds) return null
        return StopOrderProposal(stops.toList(), ordered, originalRoute, proposedRoute, comparisonAt,
            transport == Transport.MOTORCYCLE, clock(), snapshot.revision, preferences)
    }

    private suspend fun proposeLarge(stops: List<RouteStop>, departureAt: Long, transport: Transport,
        preferences: RoutePreferences): StopOrderProposal? {
        require(transport != Transport.UNKNOWN && departureAt >= 0)
        val context = coroutineContext
        val snapshot = credentials()
        val settings = largeSettings(transport)
        fun checkCurrent() {
            context.ensureActive()
            if (credentials().revision != snapshot.revision || largeSettings(transport) != settings)
                throw CancellationException("Rota ayarları değişti.")
        }
        val order = heuristicStopOrder(stops) { context.ensureActive() }
        if (order == stops.indices.toList()) return null
        val ordered = order.map(stops::get)
        val now = clock()
        require(now in 0..Long.MAX_VALUE - 120_999L && departureAt <= Long.MAX_VALUE - 999L)
        val compareAt = ((maxOf(departureAt, now + 120_000L) + 999L) / 1000L) * 1000L
        val calculate = largeRoute ?: throw RouteServiceException("Bu rota için durak sırası hesabı kullanılamıyor.")
        checkCurrent()
        val original = calculate(stops, compareAt, transport, preferences, null).requireUsablePreferences()
        checkCurrent()
        check(clock() < compareAt) { "Karşılaştırma saati geçti. Yeniden dene." }
        val proposed = calculate(ordered, compareAt, transport, preferences, original.provider).requireUsablePreferences()
        checkCurrent()
        check(clock() < compareAt) { "Karşılaştırma saati geçti. Yeniden dene." }
        check(original.stops == stops && proposed.stops == ordered &&
            original.provider == proposed.provider && original.transport == transport && proposed.transport == transport &&
            original.preferences == preferences && proposed.preferences == preferences &&
            original.avoidAreas == proposed.avoidAreas &&
            original.travelSpeedKmh == proposed.travelSpeedKmh &&
            (original.effectiveDepartureAt ?: compareAt) == compareAt &&
            (proposed.effectiveDepartureAt ?: compareAt) == compareAt) { "Rotalar aynı sağlayıcı ve ayarlara ait değil." }
        if (proposed.durationSeconds >= original.durationSeconds) return null
        return StopOrderProposal(stops, ordered, original, proposed, compareAt, false, clock(), snapshot.revision,
            preferences, heuristic = true, routeSettings = settings)
    }
}
