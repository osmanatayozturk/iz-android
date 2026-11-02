package org.iz.navigation.offline

import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout

/** Installed once in the global MapLibre HTTP seam. A retired job never falls back to unbounded I/O. */
class OfflineMapHttpRouter(private val ordinary: Call.Factory,
    val localResources: OfflineLocalResources = OfflineLocalResources()) : Call.Factory {
    private class Job(val factory: Call.Factory, val retire: () -> Unit)
    @Volatile private var job: Job? = null
    @Synchronized fun install(factory: Call.Factory, retire: () -> Unit): AutoCloseable {
        check(job == null) { "Another map download owns the transport" }
        val owner = Job(factory, retire)
        job = owner
        return object : AutoCloseable {
            private var closed = false
            override fun close() {
                synchronized(this@OfflineMapHttpRouter) {
                    if (closed) return
                    closed = true
                    if (job === owner) job = null
                }
                owner.retire()
            }
        }
    }
    override fun newCall(request: Request): Call {
        if (request.url.host == OfflineLocalResources.HOST) return localResources.newCall(request)
        val active = job
        if (active != null) return active.factory.newCall(request)
        if (request.url.host == "tiles.openfreemap.org") return DeniedCall(request)
        return ordinary.newCall(request)
    }
    private class DeniedCall(private val original: Request) : Call {
        private var executed = false
        private var cancelled = false
        override fun request() = original
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = DeniedCall(original)
        override fun cancel() { cancelled = true }
        @Synchronized private fun start() { check(!executed); executed = true }
        override fun execute(): Response { start(); throw IOException("Harita alanı için etkin indirme yok.") }
        override fun enqueue(responseCallback: Callback) {
            start(); responseCallback.onFailure(this, IOException("Harita alanı için etkin indirme yok."))
        }
    }
}
