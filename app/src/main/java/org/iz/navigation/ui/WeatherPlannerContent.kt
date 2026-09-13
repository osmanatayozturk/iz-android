@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.Place
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.RideWeatherLiveState
import org.iz.navigation.weather.RideWeatherStatus
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RoutePreferences
import org.iz.navigation.weather.matrixUnavailableReason
import org.iz.navigation.weather.geometryKey
import org.iz.navigation.weather.WeatherAssessment
import org.iz.navigation.weather.WeatherHazard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal const val WEATHER_TARGET_DESTINATION = -1
internal const val WEATHER_TARGET_NEW_VIA = -2

internal data class WeatherPlannerActions(
    val chooseStop: (Int) -> Unit,
    val useCurrentOrigin: () -> Unit,
    val removeStop: (Int) -> Unit,
    val addVia: () -> Unit,
    val pickDate: () -> Unit,
    val pickTime: () -> Unit,
    val departNow: () -> Unit,
    val calculate: () -> Unit,
    val selectDeparture: (Long) -> Unit,
    val start: () -> Unit,
    val openSettings: () -> Unit,
    val refreshLive: () -> Unit,
    val stopLive: () -> Unit,
    val selectTransport: (Transport) -> Unit = {},
    val openDirections: () -> Unit = {},
    val continueTrafficFree: () -> Unit = {},
    val suggestStopOrder: () -> Unit = {},
    val acceptStopOrder: () -> Unit = {},
    val dismissStopOrder: () -> Unit = {},
    val setPreferences: (RoutePreferences) -> Unit = {},
    val requestAlternatives: () -> Unit = {},
    val selectAlternative: (String) -> Unit = {},
    val saveRoute: () -> Unit = {},
)

@Composable
internal fun WeatherPlannerContent(
    state: WeatherPlannerState,
    live: RideWeatherLiveState,
    places: List<Place>,
    actions: WeatherPlannerActions,
    routeMap: @Composable () -> Unit,
    notificationsEnabled: Boolean = true,
    locationBusy: Boolean = false,
    canSaveRoute: Boolean = false,
) {
    val recommendation = recommendedAssessment(state.comparisons)
    val displayRoute = state.route
    val interactionLocked = state.starting || locationBusy
    LazyColumn(
        modifier = Modifier.testTag("weather_planner"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Yolculuk havası", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Rotandaki tahmini varış saatlerine göre sıcaklık, yağış ve rüzgârı karşılaştır.",
                    color = Muted,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Yolculuk türü", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    transportDisplayOrder.forEach { transport ->
                        FilterChip(
                            selected = state.transport == transport,
                            onClick = { actions.selectTransport(transport) },
                            enabled = !interactionLocked,
                            label = { Text(transport.label()) },
                            leadingIcon = { Icon(transport.icon(), null) },
                            modifier = Modifier.testTag("weather_mode_${transport.name}"),
                        )
                    }
                }
                Text("Her yolculuk türünün hava ve ses tercihleri ayrı kaydedilir.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = actions.openSettings,
                    enabled = !interactionLocked,
                    modifier = Modifier.fillMaxWidth().testTag("weather_settings"),
                ) {
                    Icon(Icons.Outlined.Settings, null)
                    Spacer(Modifier.width(8.dp))
                    Text("${state.transport.label()} · Hava ayarları")
                }
            }
        }
        item {
            val preferences = state.settings.preferences
            val activeMode = state.transport in setOf(Transport.WALK, Transport.RUN, Transport.BICYCLE)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Rota tercihleri", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(preferences.avoidHighways, { actions.setPreferences(preferences.copy(avoidHighways = !preferences.avoidHighways)) },
                        enabled = !interactionLocked, label = { Text(if (activeMode) "Otoyola girme" else "Otoyoldan kaçın") },
                        modifier = Modifier.testTag("route_avoid_highways"))
                    if (!activeMode) FilterChip(preferences.avoidTolls, { actions.setPreferences(preferences.copy(avoidTolls = !preferences.avoidTolls)) },
                        enabled = !interactionLocked, label = { Text("Ücretli yoldan kaçın") }, modifier = Modifier.testTag("route_avoid_tolls"))
                    FilterChip(preferences.avoidFerries, { actions.setPreferences(preferences.copy(avoidFerries = !preferences.avoidFerries)) },
                        enabled = !interactionLocked, label = { Text("Feribottan kaçın") }, modifier = Modifier.testTag("route_avoid_ferries"))
                }
                if (activeMode && preferences.avoidHighways) Text("Otoyolsuz rota servis haritasından doğrulanır; doğrulanamazsa başlatılmaz. Harita verileri eksik olabilir.", style = MaterialTheme.typography.bodySmall)
                Text(if (activeMode) "Feribottan kaçınma tercihi mümkün olduğunda uygulanır; kesin feribot yasağı değildir."
                    else "Otoyol, ücretli yol ve feribottan kaçınma tercihleri mümkün olduğunda uygulanır; kesin yol yasağı değildir.", style = MaterialTheme.typography.bodySmall)
                if (canSaveRoute) OutlinedButton(actions.saveRoute, enabled = state.stops.size in 2..5 && !interactionLocked,
                    modifier = Modifier.testTag("weather_save_route")) { Text("Rotayı kaydet") }
            }
        }
        if (!notificationsEnabled) {
            item {
                Text("Bildirimler kapalı. Hava uyarıları için telefon ayarlarından İz bildirimlerini aç.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (live.status != RideWeatherStatus.OFF) {
            item { WeatherLiveCard(live, actions.refreshLive, actions.stopLive, enabled = !interactionLocked) }
        }
        item {
            WeatherStopEditor(
                title = "Başlangıç",
                stop = state.stops.firstOrNull(),
                showCurrent = true,
                enabled = !interactionLocked,
                onCurrent = actions.useCurrentOrigin,
                onChoose = { actions.chooseStop(0) },
                locationBusy = locationBusy,
            )
        }
        val vias = if (state.stops.size >= 3) state.stops.subList(1, state.stops.lastIndex) else emptyList()
        vias.forEachIndexed { viaIndex, stop ->
            item(key = "via-${viaIndex + 1}") {
                WeatherStopEditor(
                    title = "Ara durak ${viaIndex + 1}",
                    stop = stop,
                    showCurrent = false,
                enabled = !interactionLocked,
                    onCurrent = {},
                    onChoose = { actions.chooseStop(viaIndex + 1) },
                    onRemove = { actions.removeStop(viaIndex + 1) },
                )
            }
        }
        item {
            WeatherStopEditor(
                title = "Varış",
                stop = state.stops.takeIf { it.size >= 2 }?.lastOrNull(),
                showCurrent = false,
                enabled = !interactionLocked,
                onCurrent = {},
                onChoose = { actions.chooseStop(WEATHER_TARGET_DESTINATION) },
            )
        }
        item {
            OutlinedButton(
                onClick = actions.addVia,
                enabled = state.stops.size in 2..4 && !interactionLocked,
                modifier = Modifier.fillMaxWidth().testTag("weather_add_via"),
            ) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.stops.size >= 5) "En fazla 3 ara durak" else "Ara durak ekle")
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Kalkış", style = MaterialTheme.typography.titleMedium)
                    Text(weatherDateTime(state.departureAt), fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = actions.departNow, enabled = !interactionLocked) { Text("Şimdi") }
                        OutlinedButton(onClick = actions.pickDate, enabled = !interactionLocked) { Text("Tarih") }
                        OutlinedButton(onClick = actions.pickTime, enabled = !interactionLocked) { Text("Saat") }
                    }
                    Text("Şimdi ile önümüzdeki 7 gün arasında bir zaman seç.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
        state.error?.let { message ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("weather_error"),
                ) {
                    Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        item {
            Button(
                onClick = actions.calculate,
                enabled = state.stops.size in 2..5 && !state.busy && !state.ordering && !interactionLocked,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("weather_calculate"),
            ) {
                Icon(Icons.Outlined.Cloud, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.busy) "Rota ve tahmin hesaplanıyor…" else "7 kalkış seçeneğini karşılaştır")
            }
        }
        if (state.stops.size in 4..5 && state.transport in setOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE)) item {
            val matrixReason = state.settings.preferences.matrixUnavailableReason(state.transport)
            OutlinedButton(actions.suggestStopOrder, enabled = matrixReason == null && !state.busy && !state.ordering && !interactionLocked,
                modifier = Modifier.testTag("weather-matrix-order")) {
                Text(if (state.ordering) "Durak sırası hesaplanıyor…" else "Daha hızlı durak sırası öner")
            }
            matrixReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.transport == Transport.MOTORCYCLE) Text("Matrix sıra önerisi otomobil tahminidir; motosiklet rotasıyla doğrulanır.",
                style = MaterialTheme.typography.bodySmall)
        }
        state.orderProposal?.let { proposal -> item {
            StopOrderProposalCard(proposal, !state.busy && !state.ordering && !interactionLocked,
                actions.acceptStopOrder, actions.dismissStopOrder)
        } }
        if (displayRoute != null) {
            item {
                OutlinedButton(actions.requestAlternatives, enabled = !state.busy && !state.loadingAlternatives && !interactionLocked,
                    modifier = Modifier.testTag("weather_route_alternatives")) {
                    Text(if (state.loadingAlternatives) "Alternatifler hesaplanıyor…" else "Alternatif rotalar")
                }
                state.alternativeMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.alternatives.forEachIndexed { index, route ->
                    FilterChip(selected = displayRoute.geometryKey() == route.geometryKey(),
                        onClick = { actions.selectAlternative(route.id) }, enabled = !state.loadingAlternatives && !interactionLocked,
                        label = { Text("Rota ${index + 1} · ${formatDistance(route.distanceMeters)} · ${formatDuration(route.durationSeconds)}") },
                        modifier = Modifier.testTag("weather_route_choice_$index"))
                }
                displayRoute.providerWarnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Planlanan rota", style = MaterialTheme.typography.titleLarge)
                        Text(
                            formatDistance(displayRoute.distanceMeters),
                            color = Muted,
                        )
                        WeatherRouteTimingDetails(displayRoute, state.effectiveDepartureAt ?: state.selectedDepartureAt ?: state.departureAt,
                            null)
                        RouteTrafficText(displayRoute)
                        routeMap()
                        Text("Yedi seçenek başlangıç rotasıyla yaklaşık karşılaştırılır. Başka bir saate dokununca o saatin rotası ve havası yeniden hesaplanır. Başlatırken güncel rota alınır.",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                        Text(
                            "Noktaya dokunarak tahmini varış anındaki hava değerlerini gör.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }
            }
        }
        if (state.comparisons.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Kalkış seçenekleri", style = MaterialTheme.typography.titleLarge)
                    Text("Geçici öneri: kişisel eşiklerinin daha kısa süre aşılması. Yaklaşık seçeneklerin sonucu kesinleştirilince değişebilir.", color = Muted)
                    state.comparisons.forEachIndexed { index, assessment ->
                        DepartureAssessmentRow(
                            assessment = assessment,
                            selected = assessment.departureAt == state.selectedDepartureAt,
                            recommended = assessment.departureAt == recommendation?.departureAt,
                            enabled = !interactionLocked,
                            onClick = { actions.selectDeparture(assessment.departureAt) },
                            modifier = Modifier.testTag("weather_candidate_$index"),
                        )
                        Text(when {
                            state.requestedDepartureAt == assessment.departureAt -> "Seçtiğim saati kesinleştir · Hesaplanıyor…"
                            assessment.departureAt in state.verifiedDepartures -> "TomTom rotası hesaplandı" + if (!assessment.complete) " · Hava verisi eksik" else ""
                            else -> "Yaklaşık · Bu saatin rotasını hesaplamak için seç"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            state.selectedAssessment?.let { selected ->
                item { WeatherSummaryCard(selected) }
            }
        }
        if (state.trafficFreeDeparture != null) item {
            OutlinedButton(actions.continueTrafficFree, enabled = !state.busy && !interactionLocked) {
                Text("Seçilen saatte trafiksiz devam et")
            }
        }
        item {
            OutlinedButton(
                onClick = actions.openDirections,
                enabled = state.stops.size in 2..5 && !interactionLocked,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("weather_directions"),
            ) {
                Icon(Icons.Outlined.Map, null)
                Spacer(Modifier.width(8.dp))
                Text("Bu duraklarla yol tarifi")
            }
        }
        item {
            Button(
                onClick = actions.start,
                enabled = state.canStart && !locationBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("weather_start"),
            ) {
                Icon(state.transport.icon(), null)
                Spacer(Modifier.width(8.dp))
                Text(when {
                    locationBusy -> "Güncel konum alınıyor…"
                    state.starting -> "Güncel rotayla başlatılıyor…"
                    else -> "${state.transport.label()} yolculuğunu şimdi başlat"
                })
            }
            if (locationBusy) Text("Hassas konum bekleniyor; bu işlem en fazla 15 saniye sürebilir.",
                modifier = Modifier.testTag("weather_location_progress"), style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text(
                "Harita © OpenStreetMap katkıcıları · Rota sağlayıcısı rota kartında · Hava: Open-Meteo",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun WeatherStopEditor(
    title: String,
    stop: RouteStop?,
    showCurrent: Boolean,
    enabled: Boolean,
    onCurrent: () -> Unit,
    onChoose: () -> Unit,
    onRemove: (() -> Unit)? = null,
    locationBusy: Boolean = false,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = Forest)
                    Text(stop?.label ?: "Henüz seçilmedi", style = MaterialTheme.typography.titleMedium)
                    stop?.let {
                        Text(
                            String.format(Locale.US, "%.5f, %.5f", it.coordinate.latitude, it.coordinate.longitude),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }
                onRemove?.let {
                    IconButton(onClick = it, enabled = enabled) { Icon(Icons.Outlined.DeleteOutline, "Ara durağı kaldır") }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showCurrent) {
                    TextButton(onClick = onCurrent, enabled = enabled) {
                        Icon(Icons.Outlined.MyLocation, null)
                        Text(if (locationBusy) "Konum alınıyor…" else "Konumum")
                    }
                }
                TextButton(onClick = onChoose, enabled = enabled) {
                    Icon(Icons.Outlined.LocationOn, null)
                    Text("Kayıtlı / Ara / Harita")
                }
            }
        }
    }
}

@Composable
private fun DepartureAssessmentRow(
    assessment: WeatherAssessment,
    selected: Boolean,
    recommended: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        label = {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(weatherTime(assessment.departureAt), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            !assessment.complete -> "Eksik veri"
                            recommended -> "Önerilen"
                            assessment.exceededSeconds <= 0.0 -> "Eşiklerin altında"
                            else -> "${formatDuration(assessment.exceededSeconds)} eşik üstü"
                        },
                        color = if (recommended) Forest else Muted,
                    )
                }
                if (assessment.complete) {
                    Text(
                        "${formatTemperature(assessment.minTemperatureC)} / ${formatTemperature(assessment.maxTemperatureC)} · Yağış %${assessment.maxPrecipitationProbabilityPercent?.roundToInt() ?: "-"} · Rüzgâr ${assessment.maxWindKmh?.roundToInt() ?: "-"} km/sa",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

@Composable
private fun WeatherSummaryCard(value: WeatherAssessment) {
    Surface(color = if (value.complete) Leaf else MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.testTag("weather_summary")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (value.complete) "Seçilen kalkışın özeti" else "Bu seçenekte tahmin eksik",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Sıcaklık ${formatTemperature(value.minTemperatureC)} – ${formatTemperature(value.maxTemperatureC)}")
            Text("En yüksek yağış olasılığı %${value.maxPrecipitationProbabilityPercent?.roundToInt() ?: "-"}")
            Text("Rüzgâr ${value.maxWindKmh?.roundToInt() ?: "-"} · hamle ${value.maxGustKmh?.roundToInt() ?: "-"} km/sa")
            if (!value.complete) Text("Eksik değerler güvenli kabul edilmez. Yolculuğu başlatabilirsin; hava uyarıları eksik veride duraklatılır.")
        }
    }
}

@Composable
private fun WeatherLiveCard(
    live: RideWeatherLiveState,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    enabled: Boolean,
) {
    val staleForecast = live.assessment?.fetchedAt?.let {
        System.currentTimeMillis() - it > 60L * 60L * 1_000L
    } ?: false
    Surface(
        color = if (live.status == RideWeatherStatus.ERROR || live.gpsStale || staleForecast) {
            MaterialTheme.colorScheme.errorContainer
        } else Leaf,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().testTag("weather_live"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(live.route?.transport?.let { "${it.label()} · Canlı rota havası" } ?: "Canlı rota havası", style = MaterialTheme.typography.titleMedium)
            Text(
                when (live.status) {
                    RideWeatherStatus.LOADING -> "Tahmin hazırlanıyor…"
                    RideWeatherStatus.READY -> "${formatDistance(live.remainingMeters)} kaldı"
                    RideWeatherStatus.ERROR -> "Hava bilgisi alınamadı"
                    RideWeatherStatus.OFF -> ""
                },
            )
            live.route?.let { WeatherRouteTimingDetails(it, it.createdAt, live) }
            live.assessment?.let { assessment ->
                Text("Sıcaklık ${formatTemperature(assessment.minTemperatureC)} – ${formatTemperature(assessment.maxTemperatureC)}")
                Text("Yağış %${assessment.maxPrecipitationProbabilityPercent?.roundToInt() ?: "-"} · Rüzgâr ${assessment.maxWindKmh?.roundToInt() ?: "-"} · hamle ${assessment.maxGustKmh?.roundToInt() ?: "-"} km/sa")
                assessment.fetchedAt?.let { Text("Tahmin alındı: ${weatherDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
            }
            if (live.gpsStale) Text("Konum güncel değil; konuma bağlı uyarılar duraklatıldı.")
            if (staleForecast) Text("Tahmin bir saatten eski; yeni hava uyarıları duraklatıldı.")
            live.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = enabled) {
                    Icon(Icons.Outlined.Refresh, null)
                    Text("Yenile")
                }
                OutlinedButton(onClick = onStop, enabled = enabled) {
                    Icon(Icons.Outlined.Stop, null)
                    Text("Havayı durdur")
                }
            }
        }
    }
}

@Composable
internal fun WeatherSampleDialog(
    sample: org.iz.navigation.weather.RouteWeatherSample,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(weatherTime(sample.arrivalAt) + " varış tahmini") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sıcaklık: ${formatTemperature(sample.reading.temperatureC)}")
                Text("Yağış: %${sample.reading.precipitationProbabilityPercent?.roundToInt() ?: "-"} · ${sample.reading.precipitationMm?.let { String.format(Locale.forLanguageTag("tr-TR"), "%.1f", it) } ?: "-"} mm")
                Text("Rüzgâr: ${sample.reading.windKmh?.roundToInt() ?: "-"} · hamle ${sample.reading.gustKmh?.roundToInt() ?: "-"} km/sa")
                Text("Yön: ${sample.reading.windDirectionDegrees?.roundToInt()?.let { "$it°" } ?: "-"}")
                if (!sample.complete) Text("Bu noktada bazı tahmin değerleri eksik.", color = MaterialTheme.colorScheme.error)
                if (sample.hazards.isNotEmpty()) Text(sample.hazards.joinToString { hazardLabel(it) }, color = Clay)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Kapat") } },
    )
}

internal fun hazardLabel(value: WeatherHazard): String = when (value) {
    WeatherHazard.RAIN -> "Yağış"
    WeatherHazard.WIND -> "Rüzgâr"
    WeatherHazard.COLD -> "Soğuk"
    WeatherHazard.HEAT -> "Sıcak"
}

internal fun weatherDateTime(value: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(value))

internal fun weatherTime(value: Long): String =
    SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(value))

internal fun formatDistance(meters: Double): String =
    if (meters < 1_000.0) "${meters.roundToInt()} m"
    else String.format(Locale.forLanguageTag("tr-TR"), "%.1f km", meters / 1_000.0)

internal fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).roundToInt().coerceAtLeast(0)
    return if (minutes >= 60) "${minutes / 60} sa ${minutes % 60} dk" else "$minutes dk"
}

private fun formatTemperature(value: Double?): String =
    value?.roundToInt()?.let { "$it°C" } ?: "—"
