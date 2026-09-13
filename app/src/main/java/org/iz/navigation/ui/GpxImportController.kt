package org.iz.navigation.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.iz.navigation.gpx.GpxImportException
import org.iz.navigation.gpx.ImportedTrack

data class GpxImportUiState(val track: ImportedTrack? = null, val loading: Boolean = false, val message: String? = null)

/** Keeps document-provider responses scoped to the user's latest explicit selection. */
internal class GpxImportController(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(GpxImportUiState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var readerJob: Job? = null

    fun show(track: ImportedTrack?) {
        generation++
        readerJob?.cancel(); readerJob = null
        mutable.value = GpxImportUiState(track)
    }
    fun read(reader: suspend () -> ImportedTrack) {
        val token = ++generation
        readerJob?.cancel()
        mutable.value = mutable.value.copy(loading = true, message = null)
        readerJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val imported = reader()
                if (token == generation) mutable.value = GpxImportUiState(imported)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (token == generation) mutable.value = mutable.value.copy(loading = false,
                    message = if (error is GpxImportException) error.message else "GPX dosyası açılamadı. Dosyayı yeniden seç.")
            } finally {
                if (token == generation) { readerJob = null; mutable.value = mutable.value.copy(loading = false) }
            }
        }.also { it.start() }
    }
}
