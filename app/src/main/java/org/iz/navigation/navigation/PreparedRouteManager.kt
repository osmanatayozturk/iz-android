package org.iz.navigation.navigation

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.iz.navigation.offline.*
import org.iz.navigation.weather.*

/** User-owned prepared geometry and one explicitly selected map corridor. Restore never starts guidance/download. */
class PreparedRouteManager(context: Context, private val navigation: JourneyNavigationCoordinator) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val directory = File(context.applicationContext.noBackupFilesDir, "prepared-route")
    private val cache = NavigationRouteCache(directory)
    private val selectionStore = PreparedSelectionStore(directory)
    val maps = OfflineCorridorSession(context.applicationContext)
    private val mapCommands = PreparedMapCommands(selectionStore, object : PreparedMapActions {
        override val pack get() = maps.state.value
        override fun download() = maps.download()
        override fun resume() = maps.resume()
        override fun pause() = maps.pause()
        override fun cancel() = maps.cancel()
        override fun delete() = maps.delete()
    })
    private val mutableState = MutableStateFlow(PreparedRouteState())
    val state = mutableState.asStateFlow()
    private var epoch = 0L
    init {
        scope.launch {
            val token = epoch
            val restored = withContext(Dispatchers.IO) {
                readStored()?.let { (route, fingerprint) -> Triple(route, fingerprint, selectionStore.read(route, fingerprint, maps.state.value)) }
            }
            if (token == epoch) mutableState.value = mutableState.value.copy(route = restored?.first,
                fingerprint = restored?.second, selection = restored?.third)
        }
        scope.launch { maps.state.collect { pack ->
            mutableState.value = mutableState.value.copy(pack = pack,
                selection = mutableState.value.selection.takeUnless { pack == null })
        } }
        scope.launch { navigation.state.collect { nav ->
            val inUse = nav.preparedRouteFingerprint != null && nav.preparedRouteFingerprint == mutableState.value.fingerprint && nav.route != null
            maps.setInUse(inUse)
            mutableState.value = mutableState.value.copy(inUse = inUse)
        } }
    }
    private fun requireOwner(generation: Long): PreparedRouteState = state.value.also {
        check(generation == it.generation && !it.busy) { "Hazır rota durumu değişti. Yeniden seç." }
    }
    private fun inUseNow(value: PreparedRouteState): Boolean = navigation.state.value.let { nav ->
        value.inUse || (nav.route != null && nav.preparedRouteFingerprint != null && nav.preparedRouteFingerprint == value.fingerprint)
    }
    suspend fun prepareRoute(route: PlannedRoute, generation: Long) {
        val before = requireOwner(generation)
        check(!inUseNow(before) && maps.state.value == null) { "Önce mevcut harita alanını kaldır veya hazır rota kullanımını bitir." }
        require(route.provider == RouteProvider.VALHALLA && route.maneuvers.isNotEmpty()) { "Yalnız dönüş talimatlı Valhalla rotası hazırlanabilir." }
        val token = ++epoch
        mutableState.value = before.copy(generation = token, busy = true, error = null)
        try {
            val stored = withContext(Dispatchers.IO) {
                cache.write(route)
                checkNotNull(readStored()) { "Hazır rota doğrulanamadı." }
            }
            if (token == epoch) mutableState.value = mutableState.value.copy(route = stored.first, fingerprint = stored.second,
                selection = null, busy = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (token == epoch) mutableState.value = before.copy(generation = token, error = "Hazır rota kaydedilemedi.") }
        finally { if (token == epoch) mutableState.value = mutableState.value.copy(busy = false) }
    }
    fun selectCorridor(selection: PreparedCorridorSelection, generation: Long) {
        val before = requireOwner(generation)
        check(!inUseNow(before))
        val route = checkNotNull(before.route)
        val fingerprint = checkNotNull(before.fingerprint)
        val line = selectedLine(route, selection)
        // UI width is EACH side; the native request uses the whole width.
        maps.prepare(OfflineCorridorRequest(route.id, fingerprint, line, selection.halfWidthMeters * 2, 10, 14))
        try {
            selectionStore.write(route, fingerprint, selection)
            mutableState.value = state.value.copy(selection = selection, generation = ++epoch, error = null)
        } catch (_: Exception) {
            mutableState.value = state.value.copy(selection = null, generation = ++epoch,
                error = "Alan seçimi kaydedilemedi. İndirmeden önce alanı yeniden uygula.")
        }
    }
    fun mapCommand(generation: Long, command: String): Boolean {
        val before = requireOwner(generation)
        val accepted = mapCommands.dispatch(before.copy(inUse = inUseNow(before)), command)
        if (accepted) mutableState.value = state.value.copy(generation = ++epoch, error = null)
        return accepted
    }
    suspend fun start(generation: Long, recordJourney: Boolean, stillCurrent: () -> Boolean = { true }): String {
        val prepared = requireOwner(generation)
        val route = checkNotNull(prepared.route); val fingerprint = checkNotNull(prepared.fingerprint)
        check(stillCurrent()) { "Başlatma isteği artık güncel değil." }
        val session = navigation.startPreparedGuidance(route, fingerprint, recordJourney, stillPrepared = {
            state.value.let { it.generation == generation && !it.busy && it.fingerprint == fingerprint && it.route == route }
        }, stillCurrent = stillCurrent)
        // Consume the start token and protect the pack before returning, independently of flow delivery order.
        maps.setInUse(true)
        mutableState.value = state.value.copy(inUse = true, generation = ++epoch)
        return session
    }
    fun mapReady(): Boolean = state.value.let { value ->
        value.route?.let { route -> value.fingerprint?.let { maps.mapReady(route.id, it) } } == true
    }
    private fun readStored(): Pair<PlannedRoute, String>? {
        val route = cache.read()?.takeIf { it.provider == RouteProvider.VALHALLA && it.maneuvers.isNotEmpty() } ?: return null
        val file = File(directory, "route.json")
        if (file.length() !in 1..8_000_000L) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(8192); var total = 0L
            while (true) { val count = input.read(bytes); if (count < 0) break
                total += count; require(total <= 8_000_000L); digest.update(bytes, 0, count)
            }
        }
        return route to digest.digest().joinToString("") { "%02x".format(it) }
    }
}

