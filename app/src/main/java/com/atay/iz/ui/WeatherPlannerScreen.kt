@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.atay.iz.ui

import android.Manifest
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atay.iz.data.DiaryRepository
import com.atay.iz.data.GeoCoordinate
import com.atay.iz.data.Place
import com.atay.iz.data.SelectedOsmPlace
import com.atay.iz.data.Transport
import com.atay.iz.integration.FullscreenMapDialog
import com.atay.iz.integration.WeatherRouteMap
import com.atay.iz.weather.RideWeatherSettings
import com.atay.iz.weather.defaultWeatherSettings
import com.atay.iz.weather.RouteStop
import com.atay.iz.weather.WeatherCoordinate
import com.atay.iz.weather.WeatherThresholds
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private enum class WeatherLocationAction { ORIGIN, START }

@Composable
fun WeatherPlannerScreen(
    onClose: () -> Unit,
    initialTransport: Transport? = null,
    onDirections: (List<RouteStop>, Transport) -> Unit = { _, _ -> },
    onNavigationStarted: () -> Unit = {},
) {
    val vm: WeatherPlannerViewModel = viewModel()
    LaunchedEffect(vm) {
        initialTransport?.takeIf { it != Transport.UNKNOWN }?.let(vm::setTransport)
    }
    WeatherPlannerScreenContent(onClose, vm, onDirections, onNavigationStarted)
}

@Composable
private fun WeatherPlannerScreenContent(
    onClose: () -> Unit,
    vm: WeatherPlannerViewModel,
    onDirections: (List<RouteStop>, Transport) -> Unit,
    onNavigationStarted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    val live by vm.liveState.collectAsStateWithLifecycle()
    val repository = remember(context) { DiaryRepository(context) }
    val places by repository.places.collectAsStateWithLifecycle(initialValue = emptyList())
    var notificationsEnabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    var pickerTarget by remember { mutableIntStateOf(0) }
    var showStopPicker by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showMapPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var selectedSample by remember { mutableStateOf<com.atay.iz.weather.RouteWeatherSample?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingLocationAction by remember { mutableStateOf<WeatherLocationAction?>(null) }
    var locationRequestVersion by remember { mutableIntStateOf(0) }
    var locationBusy by remember { mutableStateOf(false) }
    val navigationStarted by rememberUpdatedState(onNavigationStarted)

    LaunchedEffect(state.activeJourneyId) {
        if (vm.consumeNavigationStarted() != null) navigationStarted()
    }

    fun applyStop(target: Int, stop: RouteStop) {
        when {
            target == WEATHER_TARGET_NEW_VIA -> vm.addVia(stop)
            target == WEATHER_TARGET_DESTINATION && state.stops.isNotEmpty() -> {
                if (state.stops.size == 1) vm.setStop(1, stop)
                else vm.setStop(state.stops.lastIndex, stop)
            }
            target == 0 -> {
                if (state.stops.isEmpty()) vm.setStops(listOf(stop)) else vm.setStop(0, stop)
            }
            target in 1 until state.stops.lastIndex -> vm.setStop(target, stop)
            else -> localError = "Önce başlangıç noktasını seç."
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        locationRequestVersion++
    }
    fun requestLocation(action: WeatherLocationAction) {
        if (state.starting || locationBusy || pendingLocationAction != null) return
        localError = null
        locationBusy = true
        pendingLocationAction = action
        val required = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            required += Manifest.permission.ACCESS_COARSE_LOCATION
            required += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (action == WeatherLocationAction.START && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }
        if (required.isEmpty()) locationRequestVersion++ else locationPermission.launch(required.toTypedArray())
    }

    val now = System.currentTimeMillis()
    fun showDatePicker() {
        val calendar = Calendar.getInstance().apply { timeInMillis = state.departureAt.coerceAtLeast(now) }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val updated = Calendar.getInstance().apply {
                    timeInMillis = state.departureAt.coerceAtLeast(System.currentTimeMillis())
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                vm.setDeparture(updated.timeInMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).apply {
            datePicker.minDate = System.currentTimeMillis()
            datePicker.maxDate = System.currentTimeMillis() + WEATHER_PLAN_WINDOW_MS
        }.show()
    }

    fun showTimePicker() {
        val calendar = Calendar.getInstance().apply { timeInMillis = state.departureAt.coerceAtLeast(System.currentTimeMillis()) }
        TimePickerDialog(
            context,
            { _, hour, minute ->
                val updated = Calendar.getInstance().apply {
                    timeInMillis = state.departureAt.coerceAtLeast(System.currentTimeMillis())
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                vm.setDeparture(updated.timeInMillis)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true,
        ).show()
    }


    LaunchedEffect(locationRequestVersion, state.transport) {
        val action = pendingLocationAction ?: return@LaunchedEffect
        pendingLocationAction = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            localError = "Bu işlem için hassas konum izni gerekli."
            locationBusy = false
            return@LaunchedEffect
        }
        try {
            val cancellation = CancellationTokenSource()
            val completion = currentCoroutineContext().job.invokeOnCompletion { cancellation.cancel() }
            @Suppress("MissingPermission")
            val location = try {
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0L)
                    .setDurationMillis(15_000L)
                    .build()
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(request, cancellation.token).await()
            } finally {
                completion.dispose()
                cancellation.cancel()
            }
            val ageMillis = location?.let {
                ((SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
            }
            if (location == null || ageMillis == null || ageMillis > 30_000L ||
                !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy > 50f
            ) {
                localError = "Güncel ve hassas konum alınamadı. Açık alanda yeniden dene."
            } else {
                val coordinate = WeatherCoordinate(location.latitude, location.longitude)
                when (action) {
                    WeatherLocationAction.ORIGIN -> vm.setCurrentOrigin(coordinate)
                    WeatherLocationAction.START -> vm.startFromCurrentLocation(coordinate)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            localError = error.message ?: "Konum alınamadı."
        } finally {
            locationBusy = false
        }
    }

    WeatherPlannerContent(
        state = state,
        live = live,
        places = places,
        notificationsEnabled = notificationsEnabled,
        locationBusy = locationBusy,
        actions = WeatherPlannerActions(
            chooseStop = { target ->
                if (target == WEATHER_TARGET_DESTINATION && state.stops.isEmpty()) {
                    localError = "Önce başlangıç noktasını seç."
                } else {
                    pickerTarget = target
                    showStopPicker = true
                }
            },
            useCurrentOrigin = { requestLocation(WeatherLocationAction.ORIGIN) },
            removeStop = vm::removeStop,
            addVia = {
                if (state.stops.size in 2..4) {
                    pickerTarget = WEATHER_TARGET_NEW_VIA
                    showStopPicker = true
                }
            },
            pickDate = ::showDatePicker,
            pickTime = ::showTimePicker,
            departNow = vm::departNow,
            calculate = { vm.calculate() },
            selectDeparture = vm::selectDeparture,
            start = { requestLocation(WeatherLocationAction.START) },
            openSettings = { showSettings = true },
            refreshLive = { vm.refreshWeather() },
            stopLive = vm::stopWeather,
            openDirections = {
                if (!locationBusy && !state.starting && state.stops.size in 2..5) {
                    onDirections(state.stops, state.transport)
                }
            },
            selectTransport = { transport ->
                if (!locationBusy && !state.starting && transport != state.transport) {
                    pendingLocationAction = null
                    locationRequestVersion++
                    selectedSample = null
                    localError = null
                    vm.setTransport(transport)
                }
            },
        ),
        routeMap = {
            WeatherRouteMap(
                route = state.route,
                assessment = state.selectedAssessment,
                modifier = Modifier.fillMaxWidth().height(260.dp),
                onSampleClick = { selectedSample = it },
                overlay = {
                    state.route?.let { planned ->
                        WeatherRouteTimingOverlay(planned, state.selectedDepartureAt ?: state.departureAt,
                            null,
                            Modifier.align(Alignment.TopStart).padding(top = 12.dp, start = 12.dp, end = 64.dp).widthIn(max = 360.dp))
                    }
                },
            )
        },
    )

    if (showStopPicker) {
        WeatherStopSourceDialog(
            places = places,
            onDismiss = { showStopPicker = false },
            onSaved = { place ->
                applyStop(
                    pickerTarget,
                    RouteStop(place.name, WeatherCoordinate(place.latitude!!, place.longitude!!)),
                )
                showStopPicker = false
            },
            onSearch = { showStopPicker = false; showSearch = true },
            onMap = { showStopPicker = false; showMapPicker = true },
        )
    }
    if (showSearch) {
        OsmSearchDialog(
            onDismiss = { showSearch = false },
            onSelect = { value ->
                applyStop(pickerTarget, value.routeStop())
                showSearch = false
            },
        )
    }
    if (showMapPicker) {
        FullscreenMapDialog(
            title = "Haritadan nokta seç",
            onDismiss = { showMapPicker = false },
            bottomBar = {
                Text(
                    "Noktayı seçmek için haritaya uzun bas.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        ) { mapModifier ->
            WeatherRouteMap(
                route = null,
                assessment = null,
                modifier = mapModifier,
                expandable = false,
                onMapLongClick = { value ->
                    applyStop(
                        pickerTarget,
                        RouteStop("Harita noktası", WeatherCoordinate(value.latitude, value.longitude)),
                    )
                    showMapPicker = false
                },
            )
        }
    }
    if (showSettings) {
        key(state.transport, state.settings) {
            WeatherSettingsDialog(
                initial = state.settings,
                transport = state.transport,
                onDismiss = { showSettings = false },
                onSave = vm::saveSettings,
                onTestVoice = vm::testVoice,
            )
        }
    }
    selectedSample?.let { sample -> WeatherSampleDialog(sample) { selectedSample = null } }
    localError?.let { message ->
        AlertDialog(
            onDismissRequest = { localError = null },
            title = { Text("İşlem tamamlanamadı") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { localError = null }) { Text("Tamam") } },
        )
    }
}

@Composable
internal fun WeatherStopSourceDialog(
    places: List<Place>,
    onDismiss: () -> Unit,
    onSaved: (Place) -> Unit,
    onSearch: () -> Unit,
    onMap: () -> Unit,
) {
    val located = places.filter { it.latitude != null && it.longitude != null }
        .sortedBy { it.name.lowercase() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rota noktası seç") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onSearch, Modifier.fillMaxWidth()) { Text("OpenStreetMap'te ara") }
                OutlinedButton(onMap, Modifier.fillMaxWidth()) { Text("Haritadan seç") }
                Text("Kayıtlı yerler", style = MaterialTheme.typography.titleMedium)
                if (located.isEmpty()) {
                    Text("Koordinatı olan kayıtlı yer bulunmuyor.", color = Muted)
                }
                located.forEach { place ->
                    TextButton(onClick = { onSaved(place) }, modifier = Modifier.fillMaxWidth()) {
                        Text(place.name, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
internal fun WeatherSettingsDialog(
    initial: RideWeatherSettings,
    onDismiss: () -> Unit,
    onSave: (RideWeatherSettings) -> Boolean,
    onTestVoice: () -> Unit,
    transport: Transport = Transport.MOTORCYCLE,
) {
    var probability by remember { mutableStateOf(initial.thresholds.precipitationProbabilityPercent.toString()) }
    var precipitation by remember { mutableStateOf(initial.thresholds.precipitationMm.toString()) }
    var wind by remember { mutableStateOf(initial.thresholds.windKmh.toString()) }
    var gust by remember { mutableStateOf(initial.thresholds.gustKmh.toString()) }
    var cold by remember { mutableStateOf(initial.thresholds.coldC.toString()) }
    var hot by remember { mutableStateOf(initial.thresholds.hotC.toString()) }
    var voice by remember { mutableStateOf(initial.voiceEnabled) }
    var alerts by remember { mutableStateOf(initial.alertsEnabled) }
    var travelSpeed by remember {
        mutableStateOf((initial.travelSpeedKmh ?: defaultWeatherSettings(transport).travelSpeedKmh)?.toString().orEmpty())
    }
    val hasTravelSpeed = transport in listOf(Transport.WALK, Transport.RUN, Transport.BICYCLE)
    var advanced by remember { mutableStateOf(false) }
    var routeEndpoint by remember { mutableStateOf(initial.routeEndpoint) }
    var weatherEndpoint by remember { mutableStateOf(initial.weatherEndpoint) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        try {
            fun number(value: String): Double =
                value.trim().replace(',', '.').toDoubleOrNull() ?: error("Bütün eşiklere sayı gir.")
            val saved = onSave(
                RideWeatherSettings(
                    thresholds = WeatherThresholds(
                        precipitationProbabilityPercent = number(probability),
                        precipitationMm = number(precipitation),
                        windKmh = number(wind),
                        gustKmh = number(gust),
                        coldC = number(cold),
                        hotC = number(hot),
                    ),
                    voiceEnabled = voice,
                    alertsEnabled = alerts,
                    travelSpeedKmh = if (hasTravelSpeed) number(travelSpeed) else null,
                    routeEndpoint = routeEndpoint.trim(),
                    weatherEndpoint = weatherEndpoint.trim(),
                ),
            )
            if (saved) onDismiss() else error = "Değerleri ve servis adreslerini kontrol et."
        } catch (failure: Exception) {
            error = failure.message ?: "Eşikleri kontrol et."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${transport.label()} · Hava ayarları") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Bu tercihler yalnızca ${transport.label().lowercase(java.util.Locale.forLanguageTag("tr-TR"))} yolculuklarında kullanılır.")
                Text("Hava bildirimleri", style = MaterialTheme.typography.titleMedium)
                Switch(checked = alerts, onCheckedChange = { alerts = it }, modifier = Modifier.testTag("weather_alerts_switch"))
                Text("Kapattığında rota tahminini görebilirsin; hava bildirimi ve sesli uyarı verilmez.", style = MaterialTheme.typography.bodySmall)
                if (hasTravelSpeed) {
                    WeatherNumberField("Planlama hızı (km/sa)", travelSpeed) { travelSpeed = it }
                    Text("Tahmini varış saatleri ve o saatlerdeki hava bu hıza göre hesaplanır.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Bunlar kişisel bildirim tercihlerindir; resmî güvenlik sınıflandırması değildir.")
                WeatherNumberField("Yağış olasılığı (%)", probability) { probability = it }
                WeatherNumberField("Yağış miktarı (mm/saat)", precipitation) { precipitation = it }
                WeatherNumberField("Rüzgâr (km/sa)", wind) { wind = it }
                WeatherNumberField("Rüzgâr hamlesi (km/sa)", gust) { gust = it }
                WeatherNumberField("Soğuk (°C)", cold) { cold = it }
                WeatherNumberField("Sıcak (°C)", hot) { hot = it }
                TextButton(onClick = {
                    val defaults = defaultWeatherSettings(transport).thresholds
                    probability = defaults.precipitationProbabilityPercent.toString()
                    precipitation = defaults.precipitationMm.toString()
                    wind = defaults.windKmh.toString()
                    gust = defaults.gustKmh.toString()
                    cold = defaults.coldC.toString()
                    hot = defaults.hotC.toString()
                    error = null
                }, modifier = Modifier.testTag("weather_reset_thresholds")) { Text("Bu türün varsayılan eşiklerine dön") }
                Column {
                    Text("Türkçe sesli uyarı", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = voice, onCheckedChange = { voice = it })
                    Text("Kapalı başlar. Ses odağı veya Türkçe dil desteği yoksa yalnızca bildirim gösterilir.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onTestVoice) { Text("Sesi test et") }
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Gelişmiş servis adreslerini gizle" else "Gelişmiş servis adresleri")
                }
                if (advanced) {
                    OutlinedTextField(
                        value = routeEndpoint,
                        onValueChange = { routeEndpoint = it },
                        label = { Text("Valhalla HTTPS adresi") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = weatherEndpoint,
                        onValueChange = { weatherEndpoint = it },
                        label = { Text("Open-Meteo HTTPS adresi") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = ::save) { Text("Kaydet") } },
        dismissButton = { TextButton(onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun WeatherNumberField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun SelectedOsmPlace.routeStop() =
    RouteStop(name, WeatherCoordinate(latitude, longitude))
