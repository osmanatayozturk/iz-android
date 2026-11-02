package org.iz.navigation.offline

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.iz.navigation.integration.OsmMapRuntime
import okhttp3.Request

/**
 * Application-owned preparation facade. UI commands are MAIN-only; blocking provider/storage
 * work is IO. Constructor/restore never starts a job, SDK manager, recording, speech or request.
 */
class OfflineCorridorSession(context: Context, directory: File = File(context.noBackupFilesDir, "offline-corridor")) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val styles = OfflineStyleRepository(File(directory, "styles"))
    private lateinit var controller: OfflineCorridorController
    @Volatile private var worker: Job? = null
    @Volatile private var ledger: OfflineHttpLedger? = null
    @Volatile private var lease: AutoCloseable? = null
    @Volatile private var localLease: AutoCloseable? = null
    @Volatile private var localOwner: OfflinePack? = null
    @Volatile private var audited: Pair<File, AuditedOfflineStyle>? = null

    private val transferLifetime = OfflineTransferLifetime(::stopAcquisition, ::retireLocal)

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
    private val sdk = MapLibreOfflineDriver(app, styles.directory,
        { pack -> checkNotNull(styles.load(pack)).first }, { controller.state.value })
    private val preparedDriver = object : OfflineNativeDriver {
        override fun create(pack: OfflinePack, created: (OfflineDriverRegion) -> Unit, failed: (String) -> Unit) {
            prepareResources(pack, false, {
                sdk.create(pack, created) { message ->
                    if (matches(pack)) { retireTransfer(); failed(message) }
                }
            }, failed)
        }
        override fun get(id: Long, found: (OfflineDriverRegion) -> Unit, failed: (String) -> Unit) {
            val pack = controller.state.value
            if (pack == null || pack.regionId != id) { failed("Harita alanı bulunamadı."); return }
            // Delete can load an inactive region without starting a transfer or fetching resources.
            if (pack.status != OfflinePackStatus.DOWNLOADING) { sdk.get(id, found, failed); return }
            prepareResources(pack, true, {
                sdk.get(id, found) { message ->
                    if (matches(pack)) { retireTransfer(); failed(message) }
                }
            }, failed)
        }
    }
    private val lifecycle = OfflineNativeLifecycle(preparedDriver, { controller.state.value },
        { checkNotNull(audited).second.closure }, ::dispatch)
    private val native = object : OfflineNativePort {
        override fun create(plan: OfflinePack, events: OfflineRegionEvents) { lifecycle.create(plan, events) }
        override fun resume(id: Long, events: OfflineRegionEvents) { lifecycle.resume(id, events) }
        override fun pause(id: Long) {
            if (transferLifetime.nativePaused(controller.state.value, localOwner, id)) lifecycle.pause(id)
        }
        override fun delete(id: Long) { lifecycle.delete(id) }
        override fun delete(id: Long, completed: () -> Unit, failed: (String) -> Unit) {
            retireTransfer()
            val pack = controller.state.value
            lifecycle.delete(id, {
                completed()
                if (pack != null && controller.state.value == null) runCatching { styles.remove(pack) }
            }, failed)
        }
    }
    init { controller = OfflineCorridorController(FileOfflinePackStore(File(directory, "manifest")), native) }
    val state get() = controller.state

    fun prepare(request: OfflineCorridorRequest): OfflinePack {
        requireMain()
        val previous = state.value
        val plan = controller.prepare(request)
        retireTransfer()
        if (previous != null && previous.request != plan.request && previous.regionId == null) styles.remove(previous)
        audited = null
        return plan
    }
    fun download(): Boolean { requireMain(); return controller.download() }
    fun pause(): Boolean = stopCommand(cancel = false)
    fun resume(): Boolean { requireMain(); return controller.resume() }
    fun cancel(): Boolean = stopCommand(cancel = true)
    private fun stopCommand(cancel: Boolean): Boolean {
        requireMain()
        val before = state.value
        val owner = localOwner
        val stopped = if (cancel) controller.cancel() else controller.pause()
        // A journal failure can change token/state to ERROR even when the return value is false.
        transferLifetime.explicitStopped(before, state.value, owner)
        return stopped
    }
    fun delete(): Boolean {
        requireMain()
        val pack = state.value ?: return false
        if (inUse) return false
        val accepted = controller.delete()
        if (accepted) retireTransfer()
        // Native deletion remains asynchronous; success is observable only after state becomes null.
        if (accepted && controller.state.value == null) runCatching { styles.remove(pack) }
        return accepted
    }
    private var inUse = false
    fun setInUse(value: Boolean) { requireMain(); inUse = value; controller.setInUse(value) }
    fun mapReady(routeId: String, sourceFingerprint: String) = localLease != null &&
        audited?.second?.profileProof != null && controller.mapReady(routeId, sourceFingerprint)
    /** Read-only local notice action; no SDK command, provider query or online fallback. */
    suspend fun noticeText(): String? {
        val owner = withContext(Dispatchers.Main.immediate) {
            ensureActive()
            val pack = state.value ?: return@withContext null
            val profile = audited ?: return@withContext null
            val lease = localLease ?: return@withContext null
            if (!mapReady(pack.request.routeId, pack.request.sourceFingerprint) ||
                pack.closure?.styleSha256 != profile.second.closure.styleSha256) return@withContext null
            Triple(pack, profile, lease)
        } ?: return null
        val text = withContext(Dispatchers.IO) {
            ensureActive()
            styles.noticeText(owner.first)
        }
        return withContext(Dispatchers.Main.immediate) {
            ensureActive()
            val pack = owner.first
            if (state.value == pack && audited === owner.second && localLease === owner.third &&
                mapReady(pack.request.routeId, pack.request.sourceFingerprint)) text else null
        }
    }
    fun renderStyle(localOverlayStyle: String): String? {
        requireMain()
        val pack = state.value ?: return null
        if (!mapReady(pack.request.routeId, pack.request.sourceFingerprint)) return null
        val ready = audited ?: return null
        if (ready.second.closure.styleSha256 != pack.closure?.styleSha256) return null
        return ready.second.withLocalOverlays(localOverlayStyle)
    }
    fun covers(point: OfflinePoint, zoom: Double): Boolean {
        val pack = state.value ?: return false
        if (!point.latitude.isFinite() || !point.longitude.isFinite() || !zoom.isFinite() ||
            zoom < pack.request.minZoom || zoom > pack.request.maxZoom ||
            !mapReady(pack.request.routeId, pack.request.sourceFingerprint)) return false
        return pack.rings.any { ring ->
            var inside = false
            var previous = ring.last()
            for (vertex in ring) {
                if ((vertex.latitude > point.latitude) != (previous.latitude > point.latitude) &&
                    point.longitude < (previous.longitude - vertex.longitude) *
                    (point.latitude - vertex.latitude) / (previous.latitude - vertex.latitude) + vertex.longitude)
                    inside = !inside
                previous = vertex
            }
            inside
        }
    }
    private fun matches(pack: OfflinePack) = controller.state.value?.let {
        it.generation == pack.generation && it.request == pack.request && it.status == OfflinePackStatus.DOWNLOADING
    } == true

    private fun prepareResources(pack: OfflinePack, existing: Boolean, ready: () -> Unit, failed: (String) -> Unit) {
        requireMain()
        if (!matches(pack)) return
        val baseClient = OsmMapRuntime.offlineClient(app)
        val local = OsmMapRuntime.localOfflineResources(app)
        worker = scope.launch {
            var ownedLease: AutoCloseable? = null
            var ownedLocal: AutoCloseable? = null
            var handedOff = false
            try {
                fun current() {
                    ensureActive()
                    if (!matches(pack)) throw CancellationException("Retired map preparation")
                }
                current()
                val keys = styles.tileKeys(pack)
                check(styles.saveTileKeys(pack, keys))
                val key = OfflineRegionIdentity.key(pack, "0".repeat(64))
                val corpusParent = File(styles.directory, "corpus")
                require(!java.nio.file.Files.isSymbolicLink(corpusParent.toPath()) &&
                    corpusParent.canonicalFile.parentFile == styles.directory)
                val corpusDirectory = File(corpusParent, key)
                require(corpusDirectory.canonicalFile.parentFile == corpusParent.canonicalFile)
                val corpus = OfflineResourceCorpus(corpusDirectory, key, pack.downloadedBytes, ::current)
                val budget = OfflineHttpLedger(pack.downloadedBytes,
                    persist = { total ->
                        val state = controller.state.value
                        state != null && matches(pack) && total >= state.downloadedBytes &&
                            controller.recordTransfer(total - state.downloadedBytes)
                    }, initialTileKeys = keys,
                    persistTiles = { matches(pack) && styles.saveTileKeys(pack, it) })
                val calls = OfflineDownloadCalls(baseClient, budget, "Iz/offline (org.iz.navigation)") {
                    corpus.admit(it.url.toString())
                }
                withContext(Dispatchers.Main.immediate) {
                    current()
                    check(lease == null)
                    ledger = budget
                    ownedLease = OsmMapRuntime.beginOfflineTransfer(app, calls, budget::cancel)
                    lease = ownedLease
                }
                fun fetch(url: String): OfflineFetchedResource {
                    current()
                    return calls.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Map resource unavailable")
                        val bytes = response.body?.bytes() ?: throw IOException("Missing map resource")
                        current()
                        OfflineFetchedResource(bytes, System.currentTimeMillis(),
                            response.header("ETag"), response.header("Last-Modified"),
                            response.header("Cache-Control"), response.header("Expires"))
                    }
                }
                fun asset(path: String): ByteArray {
                    current()
                    require(path.isNotBlank() && !path.contains('\\') && !path.startsWith('/') &&
                        path.split('/').none { it == ".." || it == "." || it.isEmpty() })
                    return app.assets.open(OfflineLabelledProfile.ASSET_ROOT + "/" + path).use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            current(); require(output.size() + count <= 1_000_000); output.write(buffer, 0, count) }
                        output.toByteArray()
                    }
                }
                val bundle = OfflineLabelledProfile.glyphBundle(::asset)
                OfflineLabelledProfile.installNotices(styles.directory, ::asset)
                val raw = corpus.obtain(OPEN_FREE_MAP_STYLE, "style", ::fetch)
                val resolved = OfflineLabelledProfile.resolve(raw) { url -> corpus.obtain(url, "source", ::fetch) }
                val source = org.json.JSONObject(resolved.json).getJSONObject("sources").getJSONObject("openmaptiles")
                val templates = source.getJSONArray("tiles")
                require(templates.length() == 1 && source.optString("scheme", "xyz") == "xyz")
                val minimum = maxOf(pack.request.minZoom, source.optInt("minzoom", 0))
                val maximum = minOf(pack.request.maxZoom, source.optInt("maxzoom", 22))
                require(minimum <= maximum)
                val template = templates.getString(0)
                val coordinates = offlineTileCoordinates(pack.rings, minimum, maximum)
                val urls = coordinates.map { coordinate ->
                    val values = coordinate.split('/')
                    template.replace("{z}", values[0]).replace("{x}", values[1]).replace("{y}", values[2])
                }.toSet()
                require(urls.size == coordinates.size && keys.all { it in urls })
                corpus.allowTiles(urls)
                for (url in urls.sorted()) { current(); corpus.obtain(url, "vector", ::fetch) }
                val (acquisition, tiles) = corpus.seal(urls)
                val (profile, resources) = OfflineLabelledProfile.materialize(resolved, bundle, styles.directory,
                    corpusDirectory, acquisition, tiles)
                current()
                val retained = styles.load(pack)
                if (existing) require(retained != null)
                if (retained != null) require(retained.second.closure.styleSha256 == profile.closure.styleSha256)
                val pinned = styles.save(pack, profile) to profile
                val proof = requireNotNull(profile.profileProof)
                ownedLocal = local.installFiles(proof.corpusId, pack.generation, styles.directory, resources) {
                    controller.state.value?.let { live ->
                        live.generation == pack.generation && live.request == pack.request &&
                            live.status in setOf(OfflinePackStatus.DOWNLOADING, OfflinePackStatus.READY) &&
                            (live.closure == null || live.closure.styleSha256 == profile.closure.styleSha256)
                    } == true
                }
                localLease = ownedLocal
                localOwner = pack.copy(closure = profile.closure)
                withContext(Dispatchers.Main.immediate) {
                    current()
                    audited = pinned
                    ready()
                    handedOff = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (matches(pack)) {
                        retireTransfer()
                        failed("Harita alanı hazırlanamadı. Mevcut rota korundu.")
                    }
                }
            } finally {
                if (!handedOff) {
                    ownedLocal?.close()
                    if (localLease === ownedLocal) { localLease = null; localOwner = null }
                    ownedLease?.close()
                    if (lease === ownedLease) { lease = null; ledger = null }
                }
            }
        }
    }
    private fun stopAcquisition() {
        ledger?.cancel(); ledger = null
        lease?.close(); lease = null
    }
    private fun retireLocal() {
        worker?.cancel(); worker = null
        localLease?.close(); localLease = null
        localOwner = null
    }
    private fun retireTransfer() {
        stopAcquisition()
        retireLocal()
    }
    /** Application shutdown only; leaving the preparation screen need not close this app-owned owner. */
    fun close() {
        dispatch {
            retireTransfer()
            if (state.value?.status == OfflinePackStatus.DOWNLOADING) controller.cancel()
            scope.cancel()
        }
    }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    companion object {
        const val MAP_ATTRIBUTION = "OpenFreeMap · OpenMapTiles · © OpenStreetMap contributors"
    }
}
