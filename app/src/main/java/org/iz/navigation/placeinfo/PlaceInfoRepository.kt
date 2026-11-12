package org.iz.navigation.placeinfo

import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class PlaceInfoRepository(private val reader: PlaceInfoReader, private val cache: PublicPlaceCache,
    private val clock: Clock = Clock.systemUTC()) {
    internal suspend fun load(key: PlaceInfoKey, force: Boolean, minimumVersion: Long? = null, publish: (PlaceInfoState) -> Unit) {
        val cached = cache.cached(key)
        val snapshot = cached?.snapshot
        val age = snapshot?.let { clock.millis() - it.fetchedAtMillis }
        val fresh = age != null && age >= 0 && age < FRESH_MILLIS && cached.unavailableStatus == null &&
            (minimumVersion == null || snapshot.version >= minimumVersion)
        val initial = PlaceInfoState(key, snapshot, loading = force || !fresh,
            stale = snapshot != null && !fresh, unavailableStatus = cached?.unavailableStatus)
        publish(initial)
        if (!force && fresh) return
        try {
            val response = reader.read(key)
            // A reader may ignore cancellation; it must not publish OR persist a retired result.
            currentCoroutineContext().ensureActive()
            when (response) {
                is PlaceInfoRead.Found -> {
                    val found = response.snapshot
                    require(found.key == key && found.version > 0)
                    require(snapshot == null || found.version >= snapshot.version)
                    require(minimumVersion == null || found.version >= minimumVersion)
                    cache.put(found)
                    currentCoroutineContext().ensureActive()
                    publish(PlaceInfoState(key, found.copy(tags = found.tags.toMap())))
                }
                is PlaceInfoRead.Unavailable -> {
                    require(response.key == key && response.status in setOf(404,410))
                    cache.unavailable(key,response.status)
                    currentCoroutineContext().ensureActive()
                    publish(PlaceInfoState(key,snapshot,stale = snapshot != null,unavailableStatus = response.status))
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { publish(initial.copy(loading = false,stale = snapshot != null,failed = true)) }
    }
    internal suspend fun invalidate(key: PlaceInfoKey) = cache.invalidate(key)
    private companion object { const val FRESH_MILLIS = 86_400_000L }
}
