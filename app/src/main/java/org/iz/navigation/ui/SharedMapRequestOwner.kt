package org.iz.navigation.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.iz.navigation.car.SharedMapInput
import org.iz.navigation.navigation.NavigationStopSnapshot

internal data class SharedIntakeIdentity(val entryKey: String, val configuration: String,
    val routeRevision: Long, val session: NavigationStopSnapshot?)
internal data class SharedMapResolution(val busy: Boolean = false, val result: SharedMapInput? = null,
    val error: String? = null, val identity: Any? = null, val generation: Long = 0)

/** The same production owner is constructible with synthetic resolver/context; no global overrides. */
internal class SharedMapRequestOwner(private val scope: CoroutineScope,
    private val resolve: suspend (String) -> SharedMapInput?, private val identity: () -> Any?,
    private val stillCurrent: () -> Boolean = { true }) {
    private val mutable = MutableStateFlow(SharedMapResolution())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    fun submit(raw: String) {
        if (!stillCurrent() || mutable.value.busy) return
        val expected = identity()
        val token = ++generation
        job?.cancel()
        mutable.value = SharedMapResolution(busy = true, identity = expected, generation = token)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val found = resolve(raw)
                ensureActive()
                if (token == generation && stillCurrent() && identity() == expected)
                    mutable.value = SharedMapResolution(result = found,
                        error = if (found == null) "Bağlantı çözülemedi. Yeniden dene." else null,
                        identity = expected, generation = token)
                else if (token == generation) mutable.value = SharedMapResolution(error = "Bağlam değişti. Bağlantıyı yeniden dene.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == generation && stillCurrent() && identity() == expected)
                    mutable.value = SharedMapResolution(error = "Bağlantı çözülemedi. Yeniden dene.", identity = expected, generation = token)
            } finally {
                if (token == generation) { mutable.value = mutable.value.copy(busy = false); job = null }
            }
        }.also { it.start() }
    }
    fun accepts(value: SharedMapResolution) = value === mutable.value && value.generation == generation &&
        stillCurrent() && value.identity == identity()
    fun close() { generation++; job?.cancel(); job = null; mutable.value = SharedMapResolution() }
}
