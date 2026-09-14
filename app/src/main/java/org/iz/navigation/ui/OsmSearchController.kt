package org.iz.navigation.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.iz.navigation.data.SelectedOsmPlace

internal data class OsmSearchUiState(
    val query: String,
    val results: List<SelectedOsmPlace> = emptyList(),
    val busy: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
)

/** Dialog UI scope only. An edit invalidates responses even if the same text is entered again. */
internal class OsmSearchController(
    private val scope: CoroutineScope,
    initialQuery: String,
    private val search: suspend (String) -> List<SelectedOsmPlace>,
) {
    private val mutable = MutableStateFlow(OsmSearchUiState(initialQuery))
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun edit(query: String) {
        if (query == mutable.value.query) return
        cancelPending()
        mutable.value = OsmSearchUiState(query)
    }

    fun submit() {
        if (mutable.value.query.isBlank() || mutable.value.busy) return
        val request = ++generation
        val submitted = mutable.value.query.trim()
        mutable.value = OsmSearchUiState(mutable.value.query, busy = true)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val results = search(submitted)
                if (request == generation && isActive) {
                    mutable.value = mutable.value.copy(results = results, searched = true)
                }
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (request == generation && isActive) {
                    mutable.value = mutable.value.copy(error = failure.message ?: "Arama tamamlanamadı.")
                }
            }
            finally {
                if (request == generation) { job = null; mutable.value = mutable.value.copy(busy = false) }
            }
        }
        job?.start()
    }

    fun close() {
        cancelPending()
        mutable.value = OsmSearchUiState(mutable.value.query)
    }

    private fun cancelPending() {
        generation++
        job?.cancel()
        job = null
    }
}
