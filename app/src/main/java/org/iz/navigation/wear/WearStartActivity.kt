package org.iz.navigation.wear

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import org.iz.navigation.garage.GarageVehicle
import org.iz.navigation.tracking.TrackingController
import org.iz.navigation.ui.IzTheme
import org.iz.navigation.ui.label
import org.iz.navigation.wearprotocol.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Only a user-opened notification / foreground phone app can reach this non-exported activity. */
class WearStartActivity : ComponentActivity() {
    private var command by mutableStateOf<WearCommand?>(null)
    private var message by mutableStateOf("Saat isteği kontrol ediliyor…")
    private var busy by mutableStateOf(false)
    private var finished by mutableStateOf(false)
    private var permissionBlocked by mutableStateOf(false)
    private var watchName by mutableStateOf<String?>(null)
    private var vehicles by mutableStateOf<List<GarageVehicle>>(emptyList())
    private var selectedVehicleId by mutableStateOf<String?>(null)
    private var approvedVehicleId: String? = null
    private var requestKey = ""
    private val bridge get() = WearPhoneBridge.get(this)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (TrackingController.hasFineLocation(this)) begin()
        else { busy = false; permissionBlocked = true; message = "Başlatmak için hassas konum izni gerekli. İzin verip tekrar dene." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestKey = intent.getStringExtra("requestKey").orEmpty()
        selectedVehicleId = savedInstanceState?.getString("selected-vehicle")
        approvedVehicleId = savedInstanceState?.getString("approved-vehicle")
        lifecycleScope.launch {
            if (readReview()) {
                message = "Konum kaydı bu telefonda tutulacak."
                watchName = bridge.requestingWatchName(requestKey)
            }
        }
        setContent {
            LaunchedEffect(command?.id, busy, finished) {
                val current = command ?: return@LaunchedEffect
                if (!busy && !finished) {
                    val remaining = WearProtocol.COMMAND_TTL_MS - (System.currentTimeMillis() - current.requestedAt)
                    kotlinx.coroutines.delay(remaining.coerceAtLeast(0L))
                    if (command?.id == current.id && !busy && !finished) {
                        command = null
                        message = "İsteğin süresi doldu. Saatten yeni bir istek gönderebilirsin."
                    }
                }
            }
            BackHandler(enabled = command != null && !finished && !busy) {
                busy = true
                lifecycleScope.launch { bridge.cancelOnPhone(requestKey); finish() }
            }
            IzTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Saatten gelen kayıt isteği", style = MaterialTheme.typography.headlineMedium)
                        command?.let { pending ->
                            Text(watchName ?: "Eşleşmiş saat", style = MaterialTheme.typography.labelLarge)
                            pending.mode?.let { Text(it.label(), style = MaterialTheme.typography.titleLarge) }
                        }
                        Text(message)
                        if (command != null && !finished) {
                            if (vehicles.size > 1) {
                                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Araç seç", style = MaterialTheme.typography.titleMedium)
                                    vehicles.forEach { vehicle ->
                                        val selected = selectedVehicleId == vehicle.id
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("wear-start-vehicle-${vehicle.id}")
                                                .selectable(selected, enabled = !busy, role = Role.RadioButton) {
                                                    selectedVehicleId = vehicle.id
                                                },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            RadioButton(selected = selected, onClick = null, enabled = !busy)
                                            Text("${vehicle.name} · ${vehicle.transport.label()}")
                                        }
                                    }
                                }
                            } else vehicles.singleOrNull()?.let { Text("Araç · ${it.name}") }
                            if (!TrackingController.hasFineLocation(this@WearStartActivity)) {
                                Text("Başlatmak için telefonda hassas konum izni gerekir.")
                            }
                            Button(onClick = { requestPermissionsAndBegin() },
                                enabled = !busy && (vehicles.size <= 1 || vehicles.any { it.id == selectedVehicleId }),
                                modifier = Modifier.fillMaxWidth().testTag("wear-start-confirm")) {
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected-vehicle", selectedVehicleId)
        outState.putString("approved-vehicle", approvedVehicleId)
        super.onSaveInstanceState(outState)
    }

    private suspend fun readReview(): Boolean {
        val review = try { bridge.pendingStartReview(requestKey) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            command = null
            message = "Garaj okunamadı. Bu isteği telefonda yeniden açmayı dene."
            return false
        }
        command = review?.command
        vehicles = review?.vehicles.orEmpty()
        selectedVehicleId = selectedVehicleId?.takeIf { id -> vehicles.any { it.id == id } }
        if (review != null) return true
        message = "Bu isteğin süresi dolmuş veya işlem tamamlanmış. Saatten tekrar deneyebilirsin."
        return false
    }

    private fun requestPermissionsAndBegin() {
        if (command == null || busy || finished) return
        val selected = vehicles.firstOrNull { it.id == selectedVehicleId } ?: vehicles.singleOrNull()
        if (vehicles.size > 1 && selected == null) return
        approvedVehicleId = selected?.id
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
        busy = true
        val selected = approvedVehicleId
        lifecycleScope.launch {
            // Permission results can arrive before onActivityPostResumed; wait for actual foreground.
            lifecycle.withResumed { }
            val result = bridge.confirmOnPhone(requestKey, vehicleId = selected)
            message = result.message
            finished = result.code != WearResultCode.NEEDS_PHONE
            if (!finished) readReview()
            busy = false
        }
    }
}
