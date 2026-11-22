package org.iz.navigation.speed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate

/** Collect only while the unrecorded Auto map owns the location display. */
internal class AutoOsmRoadSpeedResolver(
    private val nearby: suspend (WeatherCoordinate, () -> Boolean) -> RoadRegion?,
    private val online: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(context: Context) : this(OsmRoadRepository(context.applicationContext)::nearby, osmOnlineCheck(context))

    private val requestLock = Any()
    private var nextRequestAt = Long.MIN_VALUE

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun states(fixes: Flow<NavigationFix?>, transport: Transport = Transport.CAR): Flow<RoadSpeedState> = channelFlow {
        val updates = fixes.produceIn(this)
        val matcher = LocalRoadMatcher()
        val showLimit = speedMode(transport)
        var current: NavigationFix? = null
        var region: RoadRegion? = null
        var pending: Deferred<RoadRegion?>? = null
        var nextCacheLookupAt = Long.MIN_VALUE
        var lastFixAt = Long.MIN_VALUE
        var done = false

        fun clearFix() {
            current = null
            pending?.cancel()
            pending = null
            nextCacheLookupAt = Long.MIN_VALUE
            matcher.reset()
        }

        suspend fun publish(now: Long) {
            val fix = current?.takeIf { speedFixFresh(it, now) }
            val roads = fix?.let { region?.takeIf { cached -> cached.contains(it.coordinate, now) }?.roads }
            val match = if (fix != null && roads != null) matcher.match(fix, roads) else null
            val value = match?.let { osmSpeedValue(it.road.tags, transport, it.forward) } as? OsmSpeedValue.Known
            val limit = if (fix != null && match != null && value != null) {
                RoadSpeedLimit(value.kmh, RoadSpeedSource.OSM, value.genericPosted, fix.recordedAt,
                    fix.coordinate, transport, match.identity)
            } else null
            send(RoadSpeedState(ownSpeedKmh(fix, now), limit, visible = true))
        }

        try {
            publish(clock())
            while (!done) select<Unit> {
                updates.onReceiveCatching { event ->
                    if (event.isClosed) {
                        event.exceptionOrNull()?.let { throw it }
                        done = true
                    } else {
                        val fix = event.getOrThrow()
                        val now = clock()
                        if (fix != null && fix.recordedAt <= lastFixAt) return@onReceiveCatching
                        if (fix == null || !speedFixFresh(fix, now)) {
                            clearFix()
                        } else {
                            current = fix
                            lastFixAt = fix.recordedAt
                            if (showLimit && region?.contains(fix.coordinate, now) != true) {
                                region = null
                                matcher.reset()
                                if (pending == null && now >= nextCacheLookupAt) {
                                    nextCacheLookupAt = now + CACHE_LOOKUP_INTERVAL_MS
                                    pending = async { lookup(fix.coordinate) }
                                }
                            }
                        }
                        publish(now)
                    }
                }
                pending?.onAwait { loaded ->
                    pending = null
                    val now = clock()
                    val fix = current
                    if (fix != null && speedFixFresh(fix, now) && loaded?.contains(fix.coordinate, now) == true) {
                        region = loaded
                        matcher.reset()
                        publish(now)
                    }
                }
                current?.let { fix ->
                    onTimeout((FIX_EXPIRY_MS - (clock() - fix.recordedAt)).coerceIn(0L, FIX_EXPIRY_MS)) {
                        clearFix()
                        publish(clock())
                    }
                }
            }
        } finally {
            updates.cancel()
            pending?.cancel()
        }
    }.flowOn(dispatcher).conflate().distinctUntilChanged()

    private suspend fun lookup(coordinate: WeatherCoordinate): RoadRegion? = try {
        nearby(coordinate, ::reserveRequest)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun reserveRequest(): Boolean = synchronized(requestLock) {
        val now = clock()
        if (now < nextRequestAt || !online()) return@synchronized false
        nextRequestAt = now + OSM_REQUEST_INTERVAL_MS
        true
    }

    private companion object {
        const val FIX_EXPIRY_MS = ROAD_SPEED_FIX_MAX_AGE_MS + 1L
        const val CACHE_LOOKUP_INTERVAL_MS = 5_000L
        const val OSM_REQUEST_INTERVAL_MS = 30_000L

        fun osmOnlineCheck(context: Context): () -> Boolean {
            val app = context.applicationContext
            return {
                app.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED &&
                    runCatching {
                        val connectivity = app.getSystemService(ConnectivityManager::class.java)
                        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
                        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    }.getOrDefault(false)
            }
        }
    }
}
