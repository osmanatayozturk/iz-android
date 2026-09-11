package org.iz.navigation.tracking

/** A requested cadence is not a delivered cadence until Play Services acknowledges it. */
internal class LocationRequestState {
    data class Request(val generation: Long, val intervalMillis: Long)
    private var generation = 0L
    private var pending: Request? = null
    var desiredIntervalMillis: Long? = null
        private set
    var currentIntervalMillis: Long? = null
        private set
    var retryPending = false
        private set
    val active: Boolean get() = currentIntervalMillis != null

    fun request(intervalMillis: Long): Request? {
        desiredIntervalMillis = intervalMillis
        if (pending != null || currentIntervalMillis == intervalMillis && !retryPending) return null
        return Request(++generation, intervalMillis).also { pending = it }
    }
    fun succeeded(request: Request): Boolean {
        if (pending != request) return false
        currentIntervalMillis = request.intervalMillis
        pending = null
        retryPending = desiredIntervalMillis != currentIntervalMillis
        return true
    }
    fun failed(request: Request): Boolean {
        if (pending != request) return false
        pending = null
        retryPending = true
        return true
    }
    fun stop() {
        generation++
        pending = null
        desiredIntervalMillis = null
        currentIntervalMillis = null
        retryPending = false
    }
}
