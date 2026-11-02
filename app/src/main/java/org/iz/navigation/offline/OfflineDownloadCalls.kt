package org.iz.navigation.offline

import java.io.IOException
import java.util.ArrayDeque
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer

/**
 * Shared across every MapLibre provider caller. Reservations include all in-flight responses;
 * partial/failed reads consume the same persisted budget as successful reads. New resume ledger
 * must be initialized from its manifest, never from zero. One resource is capped at512KB.
 */
class OfflineHttpLedger(initialBytes: Long = 0, private val limitBytes: Long = 50_000_000,
    private val persist: (Long) -> Boolean = { true },
    initialTileKeys: Set<String> = emptySet(),
    private val persistTiles: (Set<String>) -> Boolean = { true }) {
    private val lock = Any()
    private val active = mutableSetOf<Work>()
    private val waiting = ArrayDeque<Work>()
    private var retired = false
    private val tileKeys = initialTileKeys.toMutableSet()
    private var spent = initialBytes
    private var reserved = 0L
    val downloadedBytes: Long get() = synchronized(lock) { spent }
    init {
        require(limitBytes in 1..50_000_000 && initialBytes in 0..limitBytes && tileKeys.size <= 3000)
    }
    internal fun admitTile(key: String) = synchronized(lock) {
        if (retired) throw IOException("İndirme durduruldu.")
        if (key in tileKeys) return@synchronized
        if (tileKeys.size >= 3000) throw IOException("Harita karo sınırına ulaşıldı.")
        val next = tileKeys + key
        val saved = try { persistTiles(next) } catch (_: Exception) { false }
        if (!saved) throw IOException("Harita kaynak sayacı kaydedilemedi.")
        tileKeys += key
    }

    internal class Work(val start: (Work) -> Unit, val reject: (IOException) -> Unit, val stop: () -> Unit) {
        var allowance = 0L
        var resourceCap = 0L
        var wireRead = 0L
        var decodedRead = 0L
        var done = false
    }
    internal fun submit(work: Work) {
        val rejection = synchronized(lock) {
            when {
                retired -> IOException("İndirme durduruldu.")
                waiting.size >= 64 -> IOException("Çok fazla bekleyen harita kaynağı.")
                else -> { waiting.addLast(work); null }
            }
        }
        if (rejection != null) work.reject(rejection) else drain()
    }
    private fun drain() {
        while (true) {
            var rejection: IOException? = null
            val work = synchronized(lock) {
                if (retired || active.size >= 4 || waiting.isEmpty()) return
                val next = waiting.removeFirst()
                val available = limitBytes - spent - reserved
                if (available <= 0) rejection = IOException("Harita indirme sınırına ulaşıldı.")
                else {
                    next.allowance = minOf(512_000, available)
                    next.resourceCap = next.allowance
                    reserved += next.allowance
                    active += next
                }
                next
            }
            if (rejection != null) work.reject(rejection!!) else work.start(work)
        }
    }
    internal fun remaining(work: Work, wire: Boolean = false): Long = synchronized(lock) {
        if (retired || work.done || work !in active) throw IOException("Eski indirme isteği.")
        work.resourceCap - (if (wire) work.wireRead else work.decodedRead)
    }
    internal fun consume(work: Work, count: Long, wire: Boolean = false) = synchronized(lock) {
        if (retired || work.done || work !in active || count < 0 ||
            count > work.resourceCap - (if (wire) work.wireRead else work.decodedRead))
            throw IOException("Harita indirme sınırı aşıldı.")
        val previous = maxOf(work.wireRead, work.decodedRead)
        val wireTotal = work.wireRead + if (wire) count else 0L
        val decodedTotal = work.decodedRead + if (!wire) count else 0L
        val delta = maxOf(wireTotal, decodedTotal) - previous
        if (delta > work.allowance) throw IOException("Harita indirme sınırı aşıldı.")
        if (delta > 0) {
            val committed = try { persist(spent + delta) } catch (_: Exception) { false }
            if (!committed) throw IOException("İndirme sayacı kaydedilemedi.")
        }
        work.wireRead = wireTotal; work.decodedRead = decodedTotal
        spent += delta; reserved -= delta; work.allowance -= delta
    }
    internal fun release(work: Work) {
        synchronized(lock) {
            if (work.done) return
            work.done = true
            waiting.remove(work)
            if (active.remove(work)) { reserved -= work.allowance; work.allowance = 0 }
        }
        drain()
    }
    fun cancel() {
        val stopped = synchronized(lock) {
            retired = true
            val all = active.toList() + waiting.toList()
            waiting.clear()
            all
        }
        stopped.forEach { work ->
            work.stop()
            work.reject(IOException("İndirme durduruldu."))
            release(work)
        }
    }
}

class OfflineDownloadCalls(private val delegate: Call.Factory, private val ledger: OfflineHttpLedger,
    private val userAgent: String,
    private val admit: (Request) -> Unit = {}) : Call.Factory {
    init { require(userAgent.startsWith("Iz/") && userAgent.length in 4..200 && userAgent.none { it.code < 32 }) }

    override fun newCall(request: Request): Call = BoundedCall(request)
    private inner class BoundedCall(private val original: Request) : Call {
        @Volatile private var cancelled = false
        @Volatile private var executed = false
        @Volatile private var raw: Call? = null
        @Volatile private var work: OfflineHttpLedger.Work? = null
        private val monitor = java.lang.Object()
        private var waitingCallback: Callback? = null
        private var callbackDelivered = false
        private fun validRequest(): Request {
            val url = original.url
            if (original.method != "GET") throw IOException("Bu kaynakla harita alanı indirilemiyor.")
            try {
                OfflineStyleAudit.resourceUrl(url.toString(), emptySet())
                admit(original)
            } catch (_: IllegalArgumentException) {
                throw IOException("Bu kaynakla harita alanı indirilemiyor.")
            }
            if (cancelled) throw IOException("İndirme durduruldu.")
            val segments = url.pathSegments
            if (segments.size >= 3 && segments.last().substringBeforeLast('.').toIntOrNull() != null &&
                segments[segments.lastIndex - 1].toIntOrNull() != null &&
                segments[segments.lastIndex - 2].toIntOrNull() != null) ledger.admitTile(url.toString())
            return original.newBuilder().removeHeader("Authorization").removeHeader("Proxy-Authorization")
                .removeHeader("Cookie").header("User-Agent", userAgent).build()
        }
        @Synchronized private fun markExecuted() {
            check(!executed) { "Already Executed" }; executed = true
        }
        override fun request() = original
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout(): Timeout = raw?.timeout() ?: Timeout.NONE
        override fun clone(): Call = BoundedCall(original)
        override fun cancel() {
            cancelled = true
            raw?.cancel()
            synchronized(monitor) { monitor.notifyAll() }
            work?.let { ledger.release(it) }
            deliverFailure(IOException("İndirme durduruldu."))
        }
        private fun deliverFailure(error: IOException) {
            val callback = synchronized(this) {
                if (callbackDelivered) return
                val value = waitingCallback ?: return
                callbackDelivered = true
                value
            }
            callback.onFailure(this, error)
        }
        private fun wrapResponse(response: Response, slot: OfflineHttpLedger.Work): Response {
            try {
                if (cancelled) throw IOException("İndirme durduruldu.")
                // A delegate must also disable redirects; never accept a response from a new origin.
                if (response.request.url != original.url || response.code in 300..399)
                    throw IOException("Harita kaynağı yönlendirmesi reddedildi.")
                val body = response.body ?: throw IOException("Eksik harita kaynağı.")
                val length = body.contentLength()
                if (length > ledger.remaining(slot)) throw IOException("Harita kaynağı indirme sınırını aşıyor.")
                var exhausted = false
                val guarded = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (byteCount == 0L) return 0
                        try {
                            if (cancelled) throw IOException("İndirme durduruldu.")
                            val available = ledger.remaining(slot)
                            if (available <= 0) {
                                // Exact known end needs no speculative one-byte overread.
                                if (length >= 0 && exhausted) { ledger.release(slot); return -1 }
                                throw IOException("Harita indirme sınırına ulaşıldı.")
                            }
                            val buffer = Buffer()
                            val read = super.read(buffer, minOf(byteCount, available))
                            if (read < 0) { ledger.release(slot); return -1 }
                            ledger.consume(slot, read)
                            if (length >= 0 && bodyRead + read == length) exhausted = true
                            bodyRead += read
                            sink.write(buffer, read)
                            return read
                        } catch (error: IOException) {
                            ledger.cancel()
                            super.close()
                            ledger.release(slot)
                            throw error
                        }
                    }
                    private var bodyRead = 0L
                    override fun close() { try { super.close() } finally { ledger.release(slot) } }
                }.buffer()
                return response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = length
                    override fun source(): BufferedSource = guarded
                }).build()
            } catch (error: IOException) {
                response.close()
                ledger.release(slot)
                throw error
            }
        }
        override fun execute(): Response {
            markExecuted()
            val request = validRequest()
            var slot: OfflineHttpLedger.Work? = null
            var failure: IOException? = null
            val queued = OfflineHttpLedger.Work(
                start = { granted -> synchronized(monitor) { slot = granted; monitor.notifyAll() } },
                reject = { error -> synchronized(monitor) { failure = error; monitor.notifyAll() } },
                stop = { raw?.cancel() },
            )
            work = queued
            ledger.submit(queued)
            synchronized(monitor) {
                while (slot == null && failure == null && !cancelled) {
                    try { monitor.wait() } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); cancelled = true
                    }
                }
            }
            if (cancelled || failure != null) { ledger.release(queued); throw failure ?: IOException("İndirme durduruldu.") }
            try {
                if (cancelled) throw IOException("İndirme durduruldu.")
                return wrapResponse(delegate.newCall(request.newBuilder().tag(OfflineWireBudget::class.java, OfflineWireBudget(ledger, slot!!)).build()).also { raw = it }.execute(), slot!!)
            } catch (error: IOException) { ledger.release(queued); throw error }
        }
        override fun enqueue(responseCallback: Callback) {
            markExecuted()
            waitingCallback = responseCallback
            val request = try { validRequest() } catch (error: IOException) { deliverFailure(error); return }
            val queued = OfflineHttpLedger.Work(
                start = { granted ->
                    if (cancelled) { ledger.release(granted); deliverFailure(IOException("İndirme durduruldu.")) }
                    else try {
                        val call = delegate.newCall(request.newBuilder().tag(OfflineWireBudget::class.java, OfflineWireBudget(ledger, granted)).build())
                        raw = call
                        if (cancelled) { call.cancel(); ledger.release(granted); deliverFailure(IOException("İndirme durduruldu.")) }
                        else call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) { ledger.release(granted); deliverFailure(e) }
                            override fun onResponse(call: Call, response: Response) {
                                val wrapped = try { wrapResponse(response, granted) }
                                catch (error: IOException) { deliverFailure(error); return }
                                val accepted = synchronized(this@BoundedCall) {
                                    if (callbackDelivered || cancelled) false else { callbackDelivered = true; true }
                                }
                                if (accepted) responseCallback.onResponse(this@BoundedCall, wrapped) else wrapped.close()
                            }
                        })
                    } catch (error: IOException) { ledger.release(granted); deliverFailure(error) }
                },
                reject = ::deliverFailure,
                stop = { raw?.cancel() },
            )
            work = queued
            ledger.submit(queued)
        }
    }
}

/**
 * Network-interceptor hook runs before transparent decompression. Both byte views charge their
 * maximum, rather than double-counting gzip bytes or leaving raw transfer outside the cap.
 */
internal class OfflineWireBudget(private val ledger: OfflineHttpLedger, private val work: OfflineHttpLedger.Work) {
    fun wrap(body: ResponseBody): ResponseBody {
        val length = body.contentLength()
        if (length > ledger.remaining(work, wire = true)) throw IOException("Ham harita kaynağı sınırı aşıyor.")
        var readBytes = 0L
        var eof = false
        val guarded = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (byteCount == 0L) return 0
                try {
                    val available = ledger.remaining(work, wire = true)
                    if (available <= 0) {
                        if (length >= 0 && readBytes == length) return -1
                        throw IOException("Ham harita aktarımı sınırına ulaşıldı.")
                    }
                    val buffer = Buffer()
                    val count = super.read(buffer, minOf(byteCount, available))
                    if (count < 0) { eof = true; return -1 }
                    ledger.consume(work, count, wire = true)
                    readBytes += count; sink.write(buffer, count)
                    return count
                } catch (error: IOException) { ledger.cancel(); super.close(); throw error }
            }
            override fun close() {
                if (!eof && (length < 0 || readBytes < length)) work.stop()
                super.close()
            }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = length
            override fun source() = guarded
        }
    }
    companion object {
        fun interceptor() = okhttp3.Interceptor { chain ->
            val budget = chain.request().tag(OfflineWireBudget::class.java)
                ?: throw IOException("Harita aktarımı bütçeye bağlı değil.")
            val response = chain.proceed(chain.request())
            try { response.newBuilder().body(budget.wrap(response.body ?: throw IOException("Eksik harita kaynağı."))).build() }
            catch (error: IOException) { response.close(); throw error }
        }
    }
}
