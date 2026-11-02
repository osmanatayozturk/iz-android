package org.iz.navigation.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

data class OfflinePoint(val latitude: Double, val longitude: Double)
data class OfflineCorridorRequest(
    val routeId: String,
    val sourceFingerprint: String,
    val line: List<OfflinePoint>,
    val widthMeters: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val styleUrl: String = OPEN_FREE_MAP_STYLE,
)
enum class OfflinePackStatus { PREPARED, DOWNLOADING, PAUSED, PARTIAL, ERROR, READY }
data class OfflineResourceClosure(val styleSha256: String, val origins: Set<String>,
    val notices: Set<String>, val complete: Boolean)
data class OfflinePack(
    val request: OfflineCorridorRequest,
    val status: OfflinePackStatus,
    val rings: List<List<OfflinePoint>> = emptyList(),
    val tileCount: Int = 0,
    val downloadedBytes: Long = 0,
    val regionId: Long? = null,
    val generation: Long = 0,
    val closure: OfflineResourceClosure? = null,
    val error: String? = null,
)
interface OfflinePackStore {
    fun read(): OfflinePack?
    /** Atomically replaces one owned pack manifest, or throws without publishing success. */
    fun write(pack: OfflinePack)
    fun remove()
}
interface OfflineRegionEvents {
    fun created(id: Long)
    fun progress(completed: Long, required: Long, precise: Boolean, closure: OfflineResourceClosure)
    fun failed(message: String)
}
interface OfflineNativePort {
    fun create(plan: OfflinePack, events: OfflineRegionEvents)
    fun resume(id: Long, events: OfflineRegionEvents)
    fun pause(id: Long)
    fun delete(id: Long)
    /** Native deletion is asynchronous. Default bridge is for synchronous ports/test fixtures. */
    fun delete(id: Long, completed: () -> Unit, failed: (String) -> Unit) {
        try { delete(id); completed() } catch (_: Exception) { failed("Harita alanı kaldırılamadı.") }
    }
}

/** Single owner. Every native callback is matched to a persisted job generation. No restored command runs. */
class OfflineCorridorController(private val store: OfflinePackStore, private val native: OfflineNativePort) {
    private val restored = runCatching { store.read() }.getOrNull()?.let {
        if (it.status in setOf(OfflinePackStatus.DOWNLOADING, OfflinePackStatus.READY))
            it.copy(status = OfflinePackStatus.PARTIAL, generation = it.generation + 1, error = null) else it
    }
    private val mutableState = MutableStateFlow(restored)
    val state = mutableState.asStateFlow()
    private var epoch = restored?.generation ?: 0L
    private var inUse = false
    private var deleting = false
    init { restored?.let { runCatching { store.write(it) } } }

    @Synchronized fun prepare(request: OfflineCorridorRequest): OfflinePack {
        check(!inUse && !deleting && state.value?.status != OfflinePackStatus.DOWNLOADING)
        check(state.value?.regionId == null && (state.value?.downloadedBytes ?: 0L) == 0L) {
            "Önce kayıtlı harita alanını yönet. Devam eden indirme sayacı sıfırlanamaz."
        }
        val plan = corridorPlan(request).copy(generation = epoch + 1)
        store.write(plan)
        epoch = plan.generation
        mutableState.value = plan
        return plan
    }
    @Synchronized fun download(): Boolean =
        if (state.value?.status == OfflinePackStatus.PREPARED) begin() else false

    @Synchronized fun resume(): Boolean {
        if (!canResumeOfflinePack(state.value)) return false
        return begin()
    }
    private fun begin(): Boolean {
        val pack = state.value ?: return false
        if (deleting || pack.request.styleUrl != OPEN_FREE_MAP_STYLE || pack.tileCount !in 1..MAX_OFFLINE_TILES) return false
        val token = epoch + 1
        if (!commit(pack.copy(status = OfflinePackStatus.DOWNLOADING, generation = token, error = null))) return false
        epoch = token
        val events = events(token)
        try {
            if (pack.regionId == null) native.create(state.value!!, events) else native.resume(pack.regionId, events)
        } catch (_: Exception) { fail(token, "Harita alanı hazırlanamadı. Mevcut rota korundu."); return false }
        return true
    }
    @Synchronized fun pause(): Boolean = stop(OfflinePackStatus.PAUSED)
    @Synchronized fun cancel(): Boolean = stop(OfflinePackStatus.PARTIAL)
    private fun stop(status: OfflinePackStatus): Boolean {
        val pack = state.value ?: return false
        if (pack.status != OfflinePackStatus.DOWNLOADING || deleting) return false
        val next = pack.copy(status = status, generation = epoch + 1)
        // Retire before native callbacks, even if the journal has become unwritable.
        epoch = next.generation
        val saved = commit(next)
        if (!saved) mutableState.value = next.copy(status = OfflinePackStatus.ERROR, error = "Harita alanı kaydedilemedi.")
        pack.regionId?.let { runCatching { native.pause(it) } }
        return saved
    }
    @Synchronized fun delete(): Boolean {
        val pack = state.value ?: return false
        if (inUse || deleting) return false
        val token = ++epoch
        deleting = true
        pack.regionId?.let { runCatching { native.pause(it) } }
        val completed = {
            synchronized(this) {
                if (deleting && epoch == token) {
                    try { store.remove(); mutableState.value = null }
                    catch (_: Exception) { mutableState.value = pack.copy(status = OfflinePackStatus.ERROR, error = "Harita alanı kaldırılamadı.") }
                    deleting = false
                }
            }
            Unit
        }
        val failed: (String) -> Unit = {
            synchronized(this) {
                if (deleting && epoch == token) {
                    deleting = false
                    commit(pack.copy(status = OfflinePackStatus.ERROR, generation = token, error = "Harita alanı kaldırılamadı."))
                }
            }
        }
        return try {
            if (pack.regionId == null) completed() else native.delete(pack.regionId, completed, failed)
            true
        } catch (_: Exception) { failed(""); false }
    }
    @Synchronized fun recordTransfer(bytes: Long): Boolean {
        val pack = state.value ?: return false
        if (bytes < 0 || pack.status != OfflinePackStatus.DOWNLOADING || deleting) return false
        if (bytes > MAX_OFFLINE_BYTES - pack.downloadedBytes) { fail(epoch, LIMIT_ERROR); return false }
        if (!commit(pack.copy(downloadedBytes = pack.downloadedBytes + bytes))) {
            fail(epoch, "Harita alanı kaydedilemedi."); return false
        }
        return true
    }
    @Synchronized fun setInUse(inUse: Boolean) { this.inUse = inUse }
    @Synchronized fun mapReady(routeId: String, sourceFingerprint: String): Boolean = state.value?.let {
        !deleting && it.status == OfflinePackStatus.READY && it.regionId != null &&
            it.request.routeId == routeId && it.request.sourceFingerprint == sourceFingerprint &&
            it.tileCount in 1..MAX_OFFLINE_TILES && it.downloadedBytes in 0..MAX_OFFLINE_BYTES && validClosure(it.closure)
    } == true

    private fun events(token: Long) = object : OfflineRegionEvents {
        override fun created(id: Long) = synchronized(this@OfflineCorridorController) {
            if (!current(token)) { runCatching { native.pause(id) }; return@synchronized }
            if (id < 0 || !commit(state.value!!.copy(regionId = id))) {
                runCatching { native.pause(id) }; fail(token, "Harita alanı kaydedilemedi.")
            }
        }
        override fun progress(completed: Long, required: Long, precise: Boolean, closure: OfflineResourceClosure) =
            synchronized(this@OfflineCorridorController) {
                if (!current(token)) return@synchronized
                if (completed < 0 || required < 0) { fail(token, "Harita alanı doğrulanamadı."); return@synchronized }
                if (!precise || required == 0L || completed < required || !validClosure(closure)) return@synchronized
                val pack = state.value!!
                if (pack.regionId == null) return@synchronized
                if (commit(pack.copy(status = OfflinePackStatus.READY, closure = closure))) {
                    runCatching { native.pause(pack.regionId) }
                } else fail(token, "Harita alanı kaydedilemedi.")
            }
        override fun failed(message: String) = synchronized(this@OfflineCorridorController) {
            fail(token, "Harita alanı hazırlanamadı. Mevcut rota korundu.")
        }
    }
    private fun current(token: Long) = !deleting && token == epoch && state.value?.status == OfflinePackStatus.DOWNLOADING
    private fun fail(token: Long, error: String) {
        if (!current(token)) return
        val pack = state.value!!
        val next = pack.copy(status = OfflinePackStatus.ERROR, generation = ++epoch, error = error)
        if (!commit(next)) mutableState.value = next
        pack.regionId?.let { runCatching { native.pause(it) } }
    }
    private fun commit(pack: OfflinePack): Boolean = try {
        store.write(pack); mutableState.value = pack; true
    } catch (_: Exception) { false }
}

const val MAX_OFFLINE_BYTES = 50_000_000L
const val MAX_OFFLINE_TILES = 3000
private const val LIMIT_ERROR = "İndirme sınırı aşıldı. Harita alanını küçült."
/** Shared command/UI eligibility. Exact-cap continuation only revalidates an existing complete region;
 * the runtime must still verify every retained local resource before it can publish READY.
 */
fun canResumeOfflinePack(pack: OfflinePack?): Boolean {
    if (pack == null ||
        pack.status !in setOf(OfflinePackStatus.PAUSED, OfflinePackStatus.PARTIAL, OfflinePackStatus.ERROR) ||
        pack.error == LIMIT_ERROR) return false
    return pack.downloadedBytes in 0L until MAX_OFFLINE_BYTES ||
        (pack.downloadedBytes == MAX_OFFLINE_BYTES && pack.regionId != null && pack.regionId >= 0 &&
            validClosure(pack.closure))
}
internal fun validClosure(value: OfflineResourceClosure?) = value != null && value.complete &&
    value.styleSha256.matches(Regex("[0-9a-fA-F]{64}")) &&
    value.origins == setOf("https://tiles.openfreemap.org") &&
    value.notices.containsAll(setOf("OpenFreeMap", "OpenMapTiles", "OpenStreetMap"))

/** Conservatively count tile bbox intersections for each buffered ring, never the route's global bbox. */
internal fun corridorPlan(request: OfflineCorridorRequest): OfflinePack {
    require(request.styleUrl == OPEN_FREE_MAP_STYLE) { "Bu kaynakla harita alanı indirilemiyor." }
    require(request.routeId.isNotBlank() && request.routeId.length <= 300 &&
        request.sourceFingerprint.matches(Regex("[0-9a-fA-F]{64}")))
    require(request.line.size in 2..512 && request.widthMeters.isFinite() && request.widthMeters in 10.0..2000.0)
    require(request.minZoom in 0..16 && request.maxZoom in request.minZoom..16)
    require(request.line.all { it.latitude.isFinite() && it.longitude.isFinite() &&
        it.latitude in -84.0..84.0 && it.longitude in -179.9..179.9 })
    require(request.line.zipWithNext().all { abs(it.first.longitude - it.second.longitude) < 180 }) {
        "Bu meridyen geçişinde harita alanı hazırlanamadı."
    }
    // Work on defensive copies; rendering and tile budgeting must share exactly this geometry.
    val requestCopy = request.copy(line = request.line.toList())
    val rings = mutableListOf<List<OfflinePoint>>()
    val radius = request.widthMeters / 2
    fun shifted(p: OfflinePoint, east: Double, north: Double) = OfflinePoint(
        p.latitude + Math.toDegrees(north / 6_378_137.0),
        p.longitude + Math.toDegrees(east / (6_378_137.0 * cos(Math.toRadians(p.latitude)))),
    ).also { require(it.latitude in -85.0..85.0 && it.longitude in -180.0..180.0) }
    requestCopy.line.forEach { point ->
        val circle = (0 until 16).map { index ->
            val angle = index * 2 * PI / 16
            // Circumscribed polygon contains the requested end/join buffer.
            shifted(point, cos(angle) * radius / cos(PI / 16), sin(angle) * radius / cos(PI / 16))
        }
        rings += circle + circle.first()
    }
    requestCopy.line.zipWithNext().forEach { (a, b) ->
        val north = Math.toRadians(b.latitude - a.latitude) * 6_378_137.0
        val east = Math.toRadians(b.longitude - a.longitude) * 6_378_137.0 * cos(Math.toRadians((a.latitude + b.latitude) / 2))
        val distance = hypot(east, north)
        if (distance > 0.01) {
            val x = -north / distance * radius
            val y = east / distance * radius
            val rectangle = listOf(shifted(a, x, y), shifted(b, x, y), shifted(b, -x, -y), shifted(a, -x, -y))
            rings += rectangle + rectangle.first()
        }
    }
    val tiles = offlineTileCoordinates(rings, request.minZoom, request.maxZoom)
    return OfflinePack(requestCopy, OfflinePackStatus.PREPARED, rings.map { it.toList() }, tiles.size)
}

internal fun offlineTileCoordinates(rings: List<List<OfflinePoint>>, minZoom: Int, maxZoom: Int): Set<String> {
    require(minZoom in 0..16 && maxZoom in minZoom..16)
    val tiles = mutableSetOf<String>()
    for (ring in rings) for (zoom in minZoom..maxZoom) {
        val n = 1 shl zoom
        fun x(lon: Double) = floor((lon + 180) / 360 * n).toInt().coerceIn(0, n - 1)
        fun y(lat: Double): Int {
            val r = Math.toRadians(lat)
            return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
        }
        val x0 = x(ring.minOf { it.longitude }); val x1 = x(ring.maxOf { it.longitude })
        val y0 = y(ring.maxOf { it.latitude }); val y1 = y(ring.minOf { it.latitude })
        require((x1 - x0 + 1).toLong() * (y1 - y0 + 1) <= MAX_OFFLINE_TILES)
        for (tx in x0..x1) for (ty in y0..y1) {
            tiles += "$zoom/$tx/$ty"
            require(tiles.size <= MAX_OFFLINE_TILES) { "Harita alanı çok büyük. Alanı veya ayrıntıyı küçült." }
        }
    }
    return tiles
}
