package com.atay.iz.watch

import android.os.Bundle
import android.Manifest
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WatchActivity : ComponentActivity() {
    private val model: WatchViewModel by viewModels()
    override fun onResume() {
        super.onResume()
        WatchHealthRuntime.resume(this)
    }
    private val backgroundPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (WatchHealthRuntime.permissions(this)) armHealth() else permissionHelp()
    }
    private val sensorPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val background = WatchHealthPermissions.background(Build.VERSION.SDK_INT)
        if (!WatchHealthPermissions.foreground(Build.VERSION.SDK_INT).all { permission -> WatchHealthRuntime.has(this, permission) }) permissionHelp()
        else if (background != null && !WatchHealthRuntime.has(this, background)) {
            // Keep foreground and background sensor grants as separate requests on every supported OS.
            backgroundPermission.launch(background)
        } else armHealth()
    }

    private fun permissionHelp() {
        WatchHealthRuntime.mutableState.value = WatchHealthRuntime.mutableState.value.copy(
            status = "Sensör ve her zaman arka plan sensörü izinleri gerekli. Saatte İz uygulama izinlerini aç.")
    }

    private fun requestHealth() {
        if (WatchHealthRuntime.permissions(this)) { armHealth(); return }
        sensorPermissions.launch(buildList {
            addAll(WatchHealthPermissions.foreground(Build.VERSION.SDK_INT))
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }

    private fun armHealth() {
        runCatching { WatchHealthRuntime.arm(this) }.onFailure {
            Toast.makeText(this, "Otomatik sağlık takibi açılamadı. İzinleri kontrol et.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    model.observePhone()
                    delay(10_000)
                }
            }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val health by WatchHealthRuntime.state.collectAsStateWithLifecycle()
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) { now = System.currentTimeMillis(); delay(1_000) }
            }
            WatchScreen(state, now, model::start, model::stop, model::refresh, health,
                onArmHealth = ::requestHealth,
                onDisarmHealth = { WatchHealthRuntime.disarm(this) },
                onRebindHealth = { WatchHealthRuntime.rebindPhone(this) },
                onHealthSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"))) })
        }
    }
}
