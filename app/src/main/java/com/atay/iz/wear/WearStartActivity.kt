package com.atay.iz.wear

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.atay.iz.tracking.TrackingController
import com.atay.iz.ui.IzTheme
import com.atay.iz.wearprotocol.*
import kotlinx.coroutines.launch

/** Only a user-opened notification / foreground phone app can reach this non-exported activity. */
class WearStartActivity : ComponentActivity() {
    private var command by mutableStateOf<WearCommand?>(null)
    private var message by mutableStateOf("Saat isteği kontrol ediliyor…")
    private var busy by mutableStateOf(false)
    private var finished by mutableStateOf(false)
    private var permissionBlocked by mutableStateOf(false)
    private var requestKey = ""
    private val bridge get() = WearPhoneBridge.get(this)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (TrackingController.hasFineLocation(this)) begin()
        else { busy = false; permissionBlocked = true; message = "Başlatmak için hassas konum izni gerekli. İzin verip tekrar dene." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestKey = intent.getStringExtra("requestKey").orEmpty()
        lifecycleScope.launch {
            command = bridge.pending(requestKey)
            message = if (command == null) "Bu isteğin süresi dolmuş veya işlem tamamlanmış. Saatten tekrar deneyebilirsin."
                else "Saatindeki ${command?.mode?.label()} yolculuğunu bu telefonda kaydetmek için Başlat’a bas."
        }
        setContent {
            BackHandler(enabled = command != null && !finished && !busy) {
                busy = true
                lifecycleScope.launch { bridge.cancelOnPhone(requestKey); finish() }
            }
            IzTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Saatten yolculuk", style = MaterialTheme.typography.headlineMedium)
                        Text(message)
                        if (command != null && !finished) {
                            Text("Konum kaydı telefonda tutulur. Yürüyüşte adımlar, telefonun sensöründen ölçülür.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { requestPermissionsAndBegin() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (busy) "Başlatılıyor…" else "Başlat")
                            }
                            if (permissionBlocked) TextButton(onClick = {
                                startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:$packageName")))
                            }) { Text("İzin ayarlarını aç") }
                            TextButton(onClick = {
                                busy = true
                                lifecycleScope.launch { bridge.cancelOnPhone(requestKey); finish() }
                            }, enabled = !busy) { Text("İptal") }
                        } else Button(onClick = { finish() }) { Text("Kapat") }
                    }
                }
            }
        }
    }

    private fun requestPermissionsAndBegin() {
        busy = true
        permissionBlocked = false
        val needed = buildList {
            if (!TrackingController.hasFineLocation(this@WearStartActivity)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (command?.mode in listOf(WearMode.WALK, WearMode.RUN) && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) begin() else permissions.launch(needed.toTypedArray())
    }

    private fun begin() {
        lifecycleScope.launch {
            // Permission results can arrive before onActivityPostResumed; wait for actual foreground.
            lifecycle.withResumed { }
            val result = bridge.confirmOnPhone(requestKey)
            message = result.message
            busy = false
            finished = result.code != WearResultCode.NEEDS_PHONE
        }
    }
}
