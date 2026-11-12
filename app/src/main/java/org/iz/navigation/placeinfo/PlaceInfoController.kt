package org.iz.navigation.placeinfo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

class PlaceInfoController(private val scope: CoroutineScope, private val repository: PlaceInfoRepository) {
    private val mutable = MutableStateFlow(PlaceInfoState())
    val state: StateFlow<PlaceInfoState> = mutable
    private val lock = Any()
    private var generation = 0L
    private var active: Job? = null
    private var requiredVersion: Long? = null

    fun open(key: PlaceInfoKey, minimumVersion: Long? = null): Job? = synchronized(lock) {
        require(minimumVersion == null || minimumVersion > 0)
        if (mutable.value.key == key && requiredVersion == minimumVersion && active?.isActive == true) return@synchronized active
        retire()
        requiredVersion = minimumVersion
        mutable.value = PlaceInfoState(key = key,loading = true)
        start(key,false,generation)
    }

    fun refresh(): Job? = synchronized(lock) {
        val key = mutable.value.key ?: return@synchronized null
        retire(); start(key,true,generation)
    }

    fun contributionSucceeded(key: PlaceInfoKey): Job? = synchronized(lock) {
        val selected = mutable.value.key == key
        if (selected) retire()
        val token = generation
        // Invalidation belongs to the successful write, and survives closing the card.
        scope.launch {
            repository.invalidate(key)
            val refresh = synchronized(lock) {
                if (selected && token == generation && mutable.value.key == key) start(key,true,token) else null
            }
            refresh?.join()
        }
    }

    fun close() = synchronized(lock) { retire(); requiredVersion = null; mutable.value = PlaceInfoState() }

    private fun retire() { generation++; active?.cancel(); active = null }
    private fun start(key: PlaceInfoKey, force: Boolean, token: Long): Job {
        val minimumVersion = requiredVersion
        val job = scope.launch(start = CoroutineStart.LAZY) {
            repository.load(key,force,minimumVersion) { value -> synchronized(lock) {
                if (generation == token && mutable.value.key == key) mutable.value = value
            } }
        }
        active = job; job.start(); return job
    }
}
