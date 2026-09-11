package org.iz.navigation.car

import kotlinx.coroutines.*

/** A replacement also invalidates non-cooperative/late completions from the previous target. */
internal class LatestCarRequest(private val scope: CoroutineScope) {
    private var revision = 0L
    private var job: Job? = null

    fun cancel() { revision++; job?.cancel(); job = null }

    fun <T> submit(work: suspend () -> T, publish: (T) -> Unit, failed: (Exception) -> Unit,
        finished: () -> Unit) {
        cancel()
        val expected = revision
        job = scope.launch {
            try {
                val result = work()
                if (expected == revision && isActive) publish(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (expected == revision && isActive) failed(failure) }
            finally { if (expected == revision) finished() }
        }
    }
}
