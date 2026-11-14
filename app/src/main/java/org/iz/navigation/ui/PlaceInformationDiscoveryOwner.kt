package org.iz.navigation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.integration.MapPlaceCandidate
import org.iz.navigation.integration.MapOutlineReadResult
import org.iz.navigation.integration.mapCandidateHits
import org.iz.navigation.integration.rankMapCandidates
import org.iz.navigation.integration.validCoordinate

internal data class PlaceInformationDiscoveryState(val open: Boolean = false, val busy: Boolean = false,
    val candidates: List<MapPlaceCandidate> = emptyList(), val selectedRef: OsmRef? = null,
    val hitRefs: Set<OsmRef> = emptySet(), val message: String? = null, val retryable: Boolean = false,
    val outlineBusy: Boolean = false, val outlineRetryable: Boolean = false, val outlineMessage: String? = null,
    val generation: Long = 0, val identity: Any? = null) {
    val visibleCandidates get() = candidates.filter { hitRefs.isEmpty() || it.place.osmRef in hitRefs }
    val selectedCandidate get() = candidates.firstOrNull { it.place.osmRef == selectedRef }
    val choices get() = visibleCandidates.map { it.place }
    val overlay get() = candidates.map { it.place }
}

/** One transient selection shared by the real map and nonmodal list. IO starts only on explicit actions. */
internal class PlaceInformationDiscoveryOwner(private val scope: CoroutineScope,
    private val nearby: suspend (Double, Double) -> List<MapPlaceCandidate>,
    private val onSelect: (SelectedOsmPlace) -> Unit, private val identity: () -> Any?,
    private val stillCurrent: () -> Boolean = { true },
    private val outline: (suspend (MapPlaceCandidate) -> MapOutlineReadResult)? = null) {
    var state by mutableStateOf(PlaceInformationDiscoveryState())
        private set
    private var generation = 0L
    private var job: Job? = null
    private var lastTap: Triple<Double, Double, Double>? = null
    private var selectionGeneration = 0L
    private var outlineJob: Job? = null
    private var outlineDeadline: Job? = null
    private fun cancelOutline() {
        selectionGeneration++
        outlineJob?.cancel(); outlineJob = null
        outlineDeadline?.cancel(); outlineDeadline = null
    }

    fun tap(latitude: Double, longitude: Double, zoom: Double) {
        if (!scope.isActive || !stillCurrent() || !validCoordinate(latitude, longitude)) return
        job?.cancel()
        cancelOutline()
        val token = ++generation
        val expected = identity()
        lastTap = Triple(latitude, longitude, zoom)
        if (zoom < 16) {
            state = PlaceInformationDiscoveryState(open = true, message = "Yakındaki yerleri seçmek için haritayı yakınlaştır.", generation = token, identity = expected)
            return
        }
        state = PlaceInformationDiscoveryState(open = true, busy = true, generation = token, identity = expected)
        job = scope.launch {
            try {
                val found = withTimeoutOrNull(20_000) { nearby(latitude, longitude) } ?: error("Discovery timeout")
                ensureActive()
                if (!accepts(token, expected)) return@launch
                val result = rankMapCandidates(found, latitude, longitude)
                val single = result.candidates.singleOrNull()
                state = PlaceInformationDiscoveryState(open = single == null, candidates = result.candidates,
                    selectedRef = single?.place?.osmRef,
                    message = when { result.candidates.isEmpty() -> "Yakında bir OSM yeri veya bina bulunamadı. Yeni OSM mekânı için haritaya uzun basabilirsin."
                        result.capped -> "İlk 50 yer gösteriliyor. Daha belirgin bir noktaya dokunarak daraltabilirsin."
                        else -> null }, generation = token, identity = expected)
                // One candidate keeps the existing direct information path; no preview geometry read.
                if (single != null) onSelect(single.place)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (accepts(token, expected)) state = state.copy(busy = false, retryable = true,
                    message = "Yakındaki yerler alınamadı. Bağlantını kontrol edip yeniden dene.")
            } finally {
                if (generation == token) { state = state.copy(busy = false); job = null }
            }
        }
    }

    private fun accepts(token: Long, expected: Any?) = scope.isActive && token == generation && stillCurrent() && identity() == expected
    fun choose(place: SelectedOsmPlace, token: Long) {
        if (token != state.generation) return
        if (!accepts(token, state.identity)) { close(); return }
        if (place in state.choices) selectCandidate(place)
    }
    fun selectRendered(place: SelectedOsmPlace): Boolean = selectRendered(listOf(place))
    fun selectRendered(hits: List<SelectedOsmPlace>): Boolean {
        if (!accepts(state.generation, state.identity)) { close(); return false }
        val matched = mapCandidateHits(state.candidates, hits)
        if (matched.isEmpty()) return false
        if (matched.size == 1) {
            state = state.copy(open = true, hitRefs = emptySet())
            selectCandidate(matched.single().place)
        } else {
            cancelOutline()
            state = state.copy(open = true, selectedRef = null, hitRefs = matched.mapNotNull { it.place.osmRef }.toSet(),
                candidates = state.candidates.map { it.copy(outline = null) }, outlineBusy = false,
                outlineRetryable = false, outlineMessage = null, message = "Bu noktada birden fazla yer var. Listeden seçim yap.")
        }
        return true
    }
    private fun selectCandidate(place: SelectedOsmPlace, force: Boolean = false) {
        if (!force && state.selectedRef == place.osmRef) return
        cancelOutline()
        state = state.copy(selectedRef = place.osmRef, candidates = state.candidates.map { it.copy(outline = null) },
            outlineBusy = false, outlineRetryable = false, outlineMessage = null)
        val candidate = state.selectedCandidate ?: return
        if (place.osmRef?.type == OsmType.RELATION) {
            state = state.copy(outlineMessage = "Çok parçalı nesnenin sınırı çizilmiyor; konumu nokta olarak işaretlendi.")
            return
        }
        val read = outline ?: return
        if (place.osmRef?.type != OsmType.WAY) return
        val request = state.generation
        val expected = state.identity
        val selection = selectionGeneration
        fun current() = accepts(request, expected) && selection == selectionGeneration && state.selectedRef == place.osmRef
        state = state.copy(outlineBusy = true)
        // A sibling deadline updates UI at20s even if a blocking transport ignores cancellation.
        // The old request loses its generation before cancellation; it cannot publish a late shape.
        outlineDeadline = scope.launch {
            delay(20_000)
            if (current()) {
                selectionGeneration++
                outlineJob?.cancel(); outlineJob = null
                state = state.copy(outlineBusy = false, outlineRetryable = true,
                    outlineMessage = "Sınır 20 saniyede alınamadı. Konum noktası ve yer bilgileri kullanılabilir.")
            }
        }
        outlineJob = scope.launch {
            try {
                val result = read(candidate)
                ensureActive()
                if (!current()) return@launch
                state = state.copy(candidates = state.candidates.map { if (it.place.osmRef == place.osmRef) it.copy(outline = result.outline) else it },
                    outlineMessage = result.message, outlineRetryable = result.outline == null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current()) state = state.copy(outlineRetryable = true,
                    outlineMessage = "Sınır alınamadı. Konum noktası ve yer bilgileri kullanılabilir; istersen yeniden dene.")
            } finally {
                if (selection == selectionGeneration) {
                    outlineDeadline?.cancel(); outlineDeadline = null; outlineJob = null
                    state = state.copy(outlineBusy = false)
                }
            }
        }
    }
    fun retryOutline() {
        if (!state.outlineBusy && state.outlineRetryable && accepts(state.generation, state.identity))
            state.selectedCandidate?.let { selectCandidate(it.place, force = true) }
    }
    fun showAll() { if (accepts(state.generation, state.identity)) state = state.copy(hitRefs = emptySet(), message = null) }
    fun openInformation(token: Long = state.generation, selected: OsmRef? = state.selectedRef) {
        if (token != state.generation || selected != state.selectedRef) return
        if (!accepts(state.generation, state.identity)) { close(); return }
        state.selectedCandidate?.let { onSelect(it.place) } // Retain list, scroll and selection beneath information.
    }
    fun retry() { if (!state.busy && state.retryable) lastTap?.let { tap(it.first, it.second, it.third) } }
    fun close() {
        generation++
        cancelOutline()
        job?.cancel(); job = null
        state = PlaceInformationDiscoveryState(generation = generation)
    }
}
