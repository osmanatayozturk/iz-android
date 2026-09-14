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
import org.iz.navigation.integration.OsmServiceException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
                    mutable.value = mutable.value.copy(error = searchErrorMessage(failure))
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

// Only this screen's existing controlled exception contracts may supply a specific UI message.
// Return local literals/reconstructed numeric codes; never forward arbitrary diagnostic text.
private const val searchConnectionMessage = "Arama servisine ulaşılamadı. Bağlantını kontrol edip yeniden ara."
private const val searchGenericMessage = "Arama tamamlanamadı. Biraz sonra yeniden dene."
private const val searchQueryLimitMessage = "Arama en fazla 200 karakter olabilir."
private val controlledSearchServiceMessages = setOf(
    "Yer arama servisi beklememizi istiyor. Biraz sonra yeniden ara.",
    "Servis boş yanıt verdi.",
    "Servis yanıtı boyut sınırını aştı.",
)
private val controlledSearchStateMessages = setOf(
    "Arama sınırı kaydedilemedi.",
    "Arama bekleme süresi kaydedilemedi.",
)
private val searchHttpMessage = Regex("""Yer araması şu an kullanılamıyor \(([1-5][0-9]{2})\)\.""")

private fun searchErrorMessage(failure: Exception): String = when (failure) {
    is OsmServiceException -> controlledSearchServiceMessages.firstOrNull { it == failure.message }
        ?: searchHttpMessage.matchEntire(failure.message.orEmpty())?.let {
            "Yer araması şu an kullanılamıyor (${it.groupValues[1].toInt()})."
        } ?: searchGenericMessage
    is UnknownHostException, is SocketException, is SocketTimeoutException -> searchConnectionMessage
    is IllegalArgumentException -> if (failure.message == searchQueryLimitMessage) searchQueryLimitMessage else searchGenericMessage
    is IllegalStateException -> controlledSearchStateMessages.firstOrNull { it == failure.message } ?: searchGenericMessage
    else -> searchGenericMessage
}
