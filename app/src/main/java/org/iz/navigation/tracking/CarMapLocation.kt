package org.iz.navigation.tracking

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.iz.navigation.navigation.NavigationFix

/** One display's demand. The service and its GPS registration are shared by visible displays. */
@MainThread
internal class CarMapLocation(context: Context) {
    private val app = context.applicationContext
    private val mutableFix = MutableStateFlow<NavigationFix?>(null)
    val fix: StateFlow<NavigationFix?> = mutableFix
    private val mutableRecovery = MutableStateFlow(false)
    val recoveryNeeded: StateFlow<Boolean> = mutableRecovery
    private var connection: ServiceConnection? = null
    private var bound: CarMapLocationService.LocalBinder? = null
    private val receive: (NavigationFix?, Boolean) -> Unit = { sample, failed ->
        mutableFix.value = sample
        mutableRecovery.value = failed
    }
    private var retryBindingAt = 0L
    private var closed = false

    fun setVisible(visible: Boolean) {
        if (!visible || closed || !TrackingController.hasFineLocation(app)) {
            release()
            return
        }
        if (connection != null) {
            bound?.retryIfNeeded()
            return
        }
        if (SystemClock.elapsedRealtime() < retryBindingAt) return
        val pending = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (connection !== this) return
                bound = service as CarMapLocationService.LocalBinder
                bound?.add(receive)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection !== this) return
                bound = null
                receive(null, true)
            }
            override fun onBindingDied(name: ComponentName) {
                if (connection === this) {
                    release()
                    setVisible(true)
                }
            }
        }
        connection = pending
        val started = try {
            app.bindService(Intent(app, CarMapLocationService::class.java), pending, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) { false }
        if (!started) {
            release()
            retryBindingAt = SystemClock.elapsedRealtime() + 15_000
            receive(null, true)
        }
    }

    private fun release() {
        receive(null, false)
        retryBindingAt = 0L
        val previous = connection ?: return
        connection = null
        bound?.remove(receive)
        bound = null
        runCatching { app.unbindService(previous) }
    }

    fun close() {
        closed = true
        release()
    }
}
