package org.iz.navigation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.FuelFill
import org.iz.navigation.garage.FuelStats
import org.iz.navigation.garage.GarageRepository
import org.iz.navigation.garage.GarageVehicle
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Date
import java.util.Locale

@Composable
internal fun GarageScreen(garage: GarageRepository, onClose: () -> Unit) {
    val vehicles by garage.vehicles.collectAsStateWithLifecycle<List<GarageVehicle>?>(null)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val close = { if (selectedId != null) selectedId = null else onClose() }
    BackHandler(enabled = selectedId != null, onBack = close)
    Column(Modifier.fillMaxSize().testTag("garage-screen").padding(horizontal = 16.dp)) {
        OsmEditorHeading("Garaj", true, onBack = close)
        val rows = vehicles
        if (rows == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else {
            val selected = rows.firstOrNull { it.id == selectedId }
            if (selected != null) key(selected.id) {
                GarageVehiclePage(garage, selected, rows, onSelectVehicle = { selectedId = it }, onDeleted = { selectedId = null })
            } else LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rows.isEmpty()) item { Text("Garajda henüz araç yok.") }
                items(rows, key = { it.id }) { vehicle ->
                    Card(onClick = { selectedId = vehicle.id }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("garage-vehicle-${vehicle.id}")) {
                        Column(Modifier.padding(12.dp)) {
                            Text(vehicle.name, style = MaterialTheme.typography.titleMedium)
                            Text("${vehicle.transport.label()} · ${garageNumber(vehicle.capacityLitres)} L depo")
                        }
                    }
                }
                item { Button(onClick = { creating = true }, modifier = Modifier.testTag("garage-add-vehicle")) { Text("Araç ekle") } }
            }
            if (creating) GarageVehicleEditor(garage, null, 0.0, onClose = { creating = false }, onSaved = {
                creating = false
                selectedId = it.id
            })
        }
    }
}

@Composable
private fun GarageVehiclePage(garage: GarageRepository, vehicle: GarageVehicle, vehicles: List<GarageVehicle>,
    onSelectVehicle: (String?) -> Unit, onDeleted: () -> Unit) {
    val stats by remember(garage, vehicle.id) { garage.stats(vehicle.id) }.collectAsStateWithLifecycle<FuelStats?>(null)
    val fills by remember(garage, vehicle.id) { garage.fills(vehicle.id) }.collectAsStateWithLifecycle(emptyList())
    var editingVehicle by remember { mutableStateOf(false) }
    var deleteVehicle by remember { mutableStateOf(false) }
    var choosingFill by remember { mutableStateOf(false) }
    var addingFillFor by remember { mutableStateOf<String?>(null) }
    var fillToEdit by remember { mutableStateOf<FuelFill?>(null) }
    var fillToDelete by remember { mutableStateOf<FuelFill?>(null) }
    val sameMode = vehicles.filter { it.transport == vehicle.transport }
    val zone = ZoneId.systemDefault()
    val months = remember(fills, zone) {
        fills.sortedByDescending { it.at }.groupBy { YearMonth.from(Instant.ofEpochMilli(it.at).atZone(zone)) }
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(onClick = { onSelectVehicle(null) }) { Text("Tüm araçlar") }
            Text(vehicle.name, style = MaterialTheme.typography.titleLarge)
            Text("${vehicle.transport.label()} · ${garageNumber(vehicle.capacityLitres)} L depo")
            stats?.let { FuelStatistics(it) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (sameMode.size > 1) choosingFill = true else addingFillFor = vehicle.id },
                    modifier = Modifier.testTag("garage-add-fill")) { Text("Dolum ekle") }
                OutlinedButton(onClick = { editingVehicle = true }, enabled = stats != null) { Text("Aracı düzenle") }
            }
        }
        if (fills.isEmpty()) item { Text("Bu araç için dolum yok.") }
        months.forEach { (month, entries) ->
            item(key = month.toString()) {
                val monthLabel = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", garageLocale))
                Text("$monthLabel · ${garageNumber(entries.sumOf { it.totalTl })} TL", style = MaterialTheme.typography.titleMedium)
            }
            items(entries, key = { it.id }) { fill ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(garageDate(fill.at), style = MaterialTheme.typography.titleSmall)
                        Text("${if (fill.full) "Tam" else "Kısmi"} · ${garageNumber(fill.odometerKm)} km · ${garageNumber(fill.litres)} L")
                        Text("${garageNumber(fill.pricePerLitre)} TL/L · ${garageNumber(fill.totalTl)} TL")
                        if (fill.missedBefore) Text("Öncesinde kaydedilmemiş dolum var.")
                        Row {
                            TextButton(onClick = { fillToEdit = fill }) { Text("Düzenle") }
                            TextButton(onClick = { fillToDelete = fill }) { Text("Sil") }
                        }
                    }
                }
            }
        }
        item { TextButton(onClick = { deleteVehicle = true }) { Text("Aracı sil") } }
    }
    if (editingVehicle) GarageVehicleEditor(garage, vehicle, stats?.odometerKm ?: vehicle.odometerKm,
        onClose = { editingVehicle = false }, onSaved = { editingVehicle = false })
    if (deleteVehicle) GarageDeleteDialog("Araç ve dolumlarını sil", "Bu aracın dolumları ve araç bağlantıları silinecek.",
        onClose = { deleteVehicle = false }, execute = { garage.deleteVehicle(vehicle.id); onDeleted() })
    if (choosingFill) GarageVehicleChoice(sameMode, onClose = { choosingFill = false }, onChoose = {
        choosingFill = false
        addingFillFor = it.id
    })
    val edit = fillToEdit
    val target = vehicles.firstOrNull { it.id == (addingFillFor ?: edit?.vehicleId) }
    if (target != null) {
        val targetStats by remember(garage, target.id) { garage.stats(target.id) }.collectAsStateWithLifecycle<FuelStats?>(null)
        key(target.id, edit?.id) {
            GarageFillEditor(garage, target, edit,
                initialKm = targetStats?.odometerKm,
                onClose = { addingFillFor = null; fillToEdit = null })
        }
    }
    fillToDelete?.let { target ->
        GarageDeleteDialog("Dolumu sil", "Bu dolum silinecek ve yakıt tahminleri yeniden hesaplanacak.",
            onClose = { fillToDelete = null }, execute = { garage.deleteFill(target.id); fillToDelete = null })
    }
}

@Composable
private fun FuelStatistics(stats: FuelStats) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Sayaç ${garageNumber(stats.odometerKm)} km")
        Text("${stats.learningFullCount}/5 tam dolum", Modifier.testTag("fuel-learning-progress"))
        stats.litresPer100Km?.let { Text("Tahmini tüketim ${garageNumber(it)} L/100 km") }
        stats.fullRangeKm?.let { Text("Tahmini tam depo menzili ${garageNumber(it)} km") }
        stats.remainingRangeKm?.let { Text("Tahmini depoda kalan menzil ${garageNumber(it)} km") }
        stats.typicalIntervalKm?.let { Text("Tipik dolum aralığı ${garageNumber(it)} km") }
        stats.untilTypicalKm?.let { Text("Tipik doluma kalan ${garageNumber(it.coerceAtLeast(0.0))} km") }
        stats.tlPerKm?.let { Text("Yaklaşık ${garageNumber(it)} TL/km") }
    }
}

@Composable
private fun GarageVehicleEditor(garage: GarageRepository, existing: GarageVehicle?, initialKm: Double,
    onClose: () -> Unit, onSaved: (GarageVehicle) -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var modeName by rememberSaveable { mutableStateOf((existing?.transport ?: Transport.MOTORCYCLE).name) }
    var capacity by rememberSaveable { mutableStateOf(existing?.capacityLitres?.toString().orEmpty()) }
    var usual by rememberSaveable { mutableStateOf((existing?.typicalThresholdKm ?: 20.0).toString()) }
    var reserve by rememberSaveable { mutableStateOf((existing?.rangeThresholdKm ?: 40.0).toString()) }
    val originalKm = rememberSaveable { initialKm }
    var km by rememberSaveable { mutableStateOf(if (existing == null && originalKm == 0.0) "" else originalKm.toString()) }
    val mode = Transport.valueOf(modeName)
    val depot = capacity.garageDecimal()?.takeIf { it > 0 }
    val typicalKm = usual.garageDecimal()
    val rangeKm = reserve.garageDecimal()
    val odometer = if (existing == null && km.isBlank()) 0.0 else km.garageDecimal()
    GarageMutationDialog(if (existing == null) "Araç ekle" else "Aracı düzenle", "Kaydet",
        canCommit = name.isNotBlank() && depot != null && typicalKm != null && rangeKm != null && odometer != null,
        onClose = onClose, execute = {
            val editedKm = requireNotNull(odometer)
            val changed = if (existing == null) km.isNotBlank() else editedKm != originalKm
            val row = (existing ?: GarageVehicle(name = name, transport = mode, capacityLitres = requireNotNull(depot)))
                .copy(name = name.trim(), transport = mode, capacityLitres = requireNotNull(depot),
                    typicalThresholdKm = requireNotNull(typicalKm), rangeThresholdKm = requireNotNull(rangeKm),
                    odometerKm = if (changed) editedKm else existing?.odometerKm ?: 0.0,
                    odometerAt = if (changed) System.currentTimeMillis() else existing?.odometerAt ?: 0L)
            garage.saveVehicle(row)
            onSaved(row)
        }) {
        OutlinedTextField(name, { name = it.take(160) }, label = { Text("Araç adı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (existing == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Transport.MOTORCYCLE, Transport.CAR).forEach { transport ->
                FilterChip(mode == transport, { modeName = transport.name }, label = { Text(transport.label()) })
            }
        } else Text(existing.transport.label())
        GarageNumberField("Depo kapasitesi (L)", capacity) { capacity = it }
        GarageNumberField(if (existing == null) "Toplam kilometre (isteğe bağlı)" else "Toplam kilometre", km) { km = it }
        GarageNumberField("Tipik doluma kalan km eşiği", usual) { usual = it }
        GarageNumberField("Depoda kalan km eşiği", reserve) { reserve = it }
    }
}

@Composable
private fun GarageFillEditor(garage: GarageRepository, vehicle: GarageVehicle, existing: FuelFill?, initialKm: Double?,
    onClose: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val originalAt = rememberSaveable { existing?.at ?: System.currentTimeMillis() }
    val originalTime = remember { fillTime.format(Instant.ofEpochMilli(originalAt).atZone(zone)) }
    var time by rememberSaveable { mutableStateOf(originalTime) }
    var km by rememberSaveable { mutableStateOf((existing?.odometerKm ?: initialKm)?.toString().orEmpty()) }
    var editedKm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialKm) {
        if (existing == null && !editedKm && initialKm != null) km = initialKm.toString()
    }
    var litres by rememberSaveable { mutableStateOf(existing?.litres?.toString().orEmpty()) }
    var price by rememberSaveable { mutableStateOf(existing?.pricePerLitre?.toString().orEmpty()) }
    var full by rememberSaveable { mutableStateOf(existing?.full ?: true) }
    var missed by rememberSaveable { mutableStateOf(existing?.missedBefore ?: false) }
    val at = if (time == originalTime) originalAt else runCatching {
        LocalDateTime.parse(time.trim(), fillTime).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()?.takeIf { it >= 0 }
    val odometer = km.garageDecimal()
    val amount = litres.garageDecimal()?.takeIf { it > 0 }
    val unitPrice = price.garageDecimal()?.takeIf { it > 0 }
    val total = if (amount != null && unitPrice != null) (amount * unitPrice).takeIf(Double::isFinite) else null
    GarageMutationDialog(if (existing == null) "Dolum ekle" else "Dolumu düzenle", "Kaydet",
        canCommit = at != null && odometer != null && total != null, onClose = onClose, execute = {
            val new = FuelFill(vehicleId = vehicle.id, at = requireNotNull(at), odometerKm = requireNotNull(odometer),
                litres = requireNotNull(amount), pricePerLitre = requireNotNull(unitPrice), full = full, missedBefore = missed)
            garage.saveFill(if (existing == null) new else new.copy(id = existing.id))
            onClose()
        }) {
        Text(vehicle.name, style = MaterialTheme.typography.titleMedium)
        GarageNumberField("Toplam kilometre", km) { editedKm = true; km = it }
        GarageNumberField("Alınan litre", litres) { litres = it }
        GarageNumberField("Litre fiyatı (TL)", price) { price = it }
        total?.let { Text("Toplam ${garageNumber(it)} TL") }
        OutlinedTextField(time, { time = it.take(16) }, label = { Text("Dolum zamanı") },
            supportingText = { Text("YYYY-AA-GG SS:DD") }, singleLine = true, isError = at == null, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(full, { full = true }, label = { Text("Tam") })
            FilterChip(!full, { full = false }, label = { Text("Kısmi") })
        }
        Row { Checkbox(missed, { missed = it }); Text("Bu dolumdan önce kaydedilmemiş dolum var", Modifier.padding(top = 12.dp)) }
    }
}

@Composable
private fun GarageNumberField(label: String, text: String, onText: (String) -> Unit) {
    OutlinedTextField(text, { onText(it.take(24)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun GarageDeleteDialog(title: String, message: String, onClose: () -> Unit, execute: suspend () -> Unit) {
    GarageMutationDialog(title, "Sil", true, onClose, execute) { Text(message) }
}

@Composable
private fun GarageMutationDialog(title: String, action: String, canCommit: Boolean, onClose: () -> Unit,
    execute: suspend () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, dismissButton = { TextButton(onClose, enabled = !busy) { Text("Vazgeç") } }, confirmButton = {
        Button(enabled = canCommit && !busy, onClick = {
            if (busy) return@Button
            busy = true
            error = null
            scope.launch {
                try { execute() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (invalid: IllegalArgumentException) { error = invalid.message ?: "Bilgileri kontrol et." }
                catch (_: Exception) { error = "Garaj işlemi tamamlanamadı." }
                finally { busy = false }
            }
        }) { Text(action) }
    })
}

@Composable
internal fun JourneyGarageAssignment(journey: Journey, garage: GarageRepository,
    parked: Boolean = false, stillAssignable: () -> Boolean = { true }) {
    if (journey.status != JourneyStatus.CONFIRMED || journey.endedAt == null && !parked ||
        journey.transport !in listOf(Transport.MOTORCYCLE, Transport.CAR)) return
    val vehicles by garage.vehicles.collectAsStateWithLifecycle<List<GarageVehicle>?>(null)
    val assigned by remember(garage, journey.id) { garage.assignedVehicleId(journey.id) }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val matches = vehicles?.filter { it.transport == journey.transport } ?: return
    var choosing by remember(journey.id) { mutableStateOf(false) }
    var busy by remember(journey.id) { mutableStateOf(false) }
    var failed by remember(journey.id) { mutableStateOf(false) }
    val vehicle = matches.firstOrNull { it.id == assigned }
    Column {
        Text(vehicle?.let { "Araç · ${it.name}" } ?: "Araç atanmamış", Modifier.testTag("journey-garage-assignment"))
        if (matches.isNotEmpty()) TextButton(onClick = { choosing = true }, enabled = !busy) {
            Text(if (vehicle == null) "Araç ata" else "Aracı değiştir")
        }
        if (failed) Text("Araç atanamadı. Yeniden dene.", color = MaterialTheme.colorScheme.error)
    }
    if (choosing) GarageVehicleChoice(matches, onClose = { choosing = false }, onChoose = { target ->
        if (choosing && !busy && stillAssignable()) {
            choosing = false
            busy = true
            failed = false
            scope.launch {
                try { garage.assignJourney(journey.id, target.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }
    })
}

private val garageLocale = Locale.forLanguageTag("tr-TR")
private val fillTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
private fun garageDate(at: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, garageLocale).format(Date(at))
internal fun garageNumber(value: Double) = String.format(garageLocale, "%.2f", value)
private fun String.garageDecimal(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
