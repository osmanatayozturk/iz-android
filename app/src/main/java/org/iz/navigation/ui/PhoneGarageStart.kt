package org.iz.navigation.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.iz.navigation.IzApplication
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.FuelOffer
import org.iz.navigation.garage.GarageVehicle
import org.iz.navigation.navigation.navigationReplacementKey
import org.iz.navigation.navigation.NavigationState
import kotlin.math.ceil

internal fun canAssignParkedVehicle(state: NavigationState): Boolean =
    state.recording && !state.simulation && !state.gpsStale &&
        (state.paused || state.plannedBreak != null) && state.fix?.speedMps?.let { it in 0f..0.5f } == true

internal class PhoneGarageStart(private val app: IzApplication) {
    data class Request(val options: List<GarageVehicle>, val result: CompletableDeferred<String>)

    private val mutableRequest = MutableStateFlow<Request?>(null)
    val request = mutableRequest.asStateFlow()

    suspend fun start(transport: Transport, stillCurrent: () -> Boolean = { true },
        action: suspend (validate: () -> Boolean) -> String): String {
        val previous = app.navigation.state.value
        fun current() = stillCurrent() && app.navigation.state.value.let {
            it.navigationReplacementKey() == previous.navigationReplacementKey() &&
                it.sessionId == previous.sessionId && it.journey?.id == previous.journey?.id &&
                it.routeRevision == previous.routeRevision && it.sessionTransport == previous.sessionTransport &&
                it.guidance == previous.guidance && it.recording == previous.recording && it.simulation == previous.simulation
        }
        if (!current()) throw CancellationException("Başlatma isteği değişti.")
        if (transport != Transport.CAR && transport != Transport.MOTORCYCLE || previous.simulation) return action(::current)
        val vehicles = app.garage.vehicles.first().filter { it.transport == transport }
        if (!current()) throw CancellationException("Başlatma isteği değişti.")
        val explicitlySelected = vehicles.size > 1
        val selected = if (explicitlySelected) {
            val choice = Request(vehicles, CompletableDeferred())
            if (!mutableRequest.compareAndSet(null, choice)) throw CancellationException("Araç seçimi sürüyor.")
            val id = try { choice.result.await() }
            finally { mutableRequest.compareAndSet(choice, null) }
            check(app.garage.vehicles.first().any { it.id == id && it.transport == transport }) {
                "Seçilen araç artık garajda yok."
            }
            id
        } else vehicles.singleOrNull()?.id
        if (!current()) throw CancellationException("Başlatma isteği değişti.")
        val sessionId = action(::current)
        if (selected == null) return sessionId
        val session = app.navigation.state.value.takeIf { it.sessionId == sessionId && !it.simulation }
            ?: return sessionId
        // Only the recording's creation can make an implicit persistent assignment.
        if (!explicitlySelected && (session.journey != null || session.recording || session.recordingPending)) return sessionId
        try {
            app.garage.bindSession(sessionId, session.journey?.id, selected, transport,
                explicitChoice = explicitlySelected)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            Toast.makeText(app, "Sürüş başladı. Araç bağlanamadı, yolculuk ayrıntısından yeniden dene.",
                Toast.LENGTH_LONG).show()
        }
        return sessionId
    }

    fun dismiss() { mutableRequest.value?.result?.cancel() }
}

@Composable
internal fun PhoneGarageChoice(owner: PhoneGarageStart) {
    val request by owner.request.collectAsStateWithLifecycle()
    val active = request ?: return
    GarageVehicleChoice(active.options, onClose = { active.result.cancel() },
        onChoose = { active.result.complete(it.id) })
}

@Composable
internal fun GarageVehicleChoice(vehicles: List<GarageVehicle>, onClose: () -> Unit, onChoose: (GarageVehicle) -> Unit) {
    AlertDialog(onDismissRequest = onClose, modifier = Modifier.testTag("garage-vehicle-choice"),
        title = { Text("Araç seç") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                vehicles.forEach { vehicle ->
                    TextButton(onClick = { onChoose(vehicle) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("garage-choose-${vehicle.id}")) {
                        Text("${vehicle.name} · ${vehicle.transport.label()}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClose) { Text("Vazgeç") } })
}

@Composable
internal fun PhoneFuelOfferBanner(app: IzApplication, modifier: Modifier = Modifier) {
    val controller = app.fuelOffers
    val raw by controller.offer.collectAsStateWithLifecycle()
    val autoVisible by controller.autoVisible.collectAsStateWithLifecycle()
    val offer = raw?.takeIf { !autoVisible && it.expiresAtElapsedMs > SystemClock.elapsedRealtime() } ?: return
    val scope = rememberCoroutineScope()
    var acting by remember(offer.id) { mutableStateOf(false) }
    fun current() = !controller.autoVisible.value && controller.offer.value?.id == offer.id &&
        SystemClock.elapsedRealtime() < offer.expiresAtElapsedMs
    FuelOfferContent(offer, !acting, modifier,
        onAccept = {
            if (!acting && current()) {
                acting = true
                scope.launch {
                    try { if (current()) controller.accept(offer.id) }
                    finally { acting = false }
                }
            }
        }, onReject = { if (!acting && current()) controller.reject(offer.id) })
}

@Composable
private fun FuelOfferContent(offer: FuelOffer, enabled: Boolean, modifier: Modifier,
    onAccept: () -> Unit, onReject: () -> Unit) {
    ElevatedCard(modifier.widthIn(max = 480.dp).fillMaxWidth().testTag("phone-fuel-offer")) {
        Column(Modifier.padding(12.dp)) {
            Text(offer.title, style = MaterialTheme.typography.titleMedium)
            val additional = offer.additionalSeconds
            Text(if (additional != null) "Rotaya ek ${ceil(additional / 60).toInt()} dk"
                else "${garageNumber(offer.distanceToStationMeters / 1000)} km uzakta",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onAccept, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("phone-fuel-accept")) {
                    Text("İstasyona uğra")
                }
                TextButton(onReject, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("phone-fuel-reject")) {
                    Text("Reddet")
                }
            }
        }
    }
}
