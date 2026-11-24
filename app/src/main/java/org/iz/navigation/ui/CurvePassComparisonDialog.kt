package org.iz.navigation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import org.iz.navigation.data.RecordedCurveDetail
import org.iz.navigation.integration.CurvePassCandidate
import org.iz.navigation.integration.CurvePassComparison
import org.iz.navigation.integration.CurvePassComparisonRepository
import org.iz.navigation.integration.CurvePassSample
import org.iz.navigation.integration.CurvePassSummary
import org.iz.navigation.lean.LeanMount
import org.iz.navigation.weather.WeatherCoordinate
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

@Composable
internal fun CurvePassComparisonDialog(curve: RecordedCurveDetail, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { CurvePassComparisonRepository(context) }
    var candidate by remember(curve) { mutableStateOf<CurvePassCandidate?>(null) }
    var retry by remember(curve) { mutableIntStateOf(0) }
    val candidates by produceState<Result<List<CurvePassCandidate>>?>(null, curve, retry) {
        value = null
        value = try { Result.success(repository.candidates(curve)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
    val back = { if (candidate != null) candidate = null else onClose() }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().testTag("curve-pass-comparison")) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OsmEditorHeading("Viraj geçişlerini karşılaştır", true, onBack = back)
                val picked = candidate
                if (picked != null) key(picked) {
                    val comparison by produceState<Result<CurvePassComparison?>?>(null, curve, picked) {
                        value = try { Result.success(repository.compare(curve, picked)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { Result.failure(error) }
                    }
                    val result = comparison
                    val data = result?.getOrNull()
                    when {
                        result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        data != null && data.samples.size >= 2 -> CurvePassComparisonContent(data, curve.start.at, picked.at)
                        else -> Text(if (result.isFailure) "Geçiş karşılaştırması okunamadı."
                            else "Bu geçiş artık güvenilir biçimde karşılaştırılamıyor.", Modifier.padding(vertical = 12.dp))
                    }
                } else {
                    val result = candidates
                    when {
                        result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        result.isFailure -> {
                            Text("Önceki geçişler okunamadı.", Modifier.padding(top = 12.dp))
                            TextButton(onClick = { retry++ }) { Text("Yeniden dene") }
                        }
                        result.getOrThrow().isEmpty() -> Text("Karşılaştırılabilir geçiş bulunamadı.", Modifier.padding(top = 12.dp))
                        else -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(result.getOrThrow(), key = { it.curve.id }) { prior ->
                                Card(onClick = { candidate = prior }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("curve-pass-candidate")) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(passDate(prior.at), style = MaterialTheme.typography.titleSmall)
                                        if (prior.journeyTitle.isNotBlank()) Text(prior.journeyTitle)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CurvePassComparisonContent(comparison: CurvePassComparison, selectedAt: Long, previousAt: Long) {
    val samples = comparison.samples
    var index by remember(samples) { mutableIntStateOf(0) }
    val point = samples[index]
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("İki geçişte aynı mesafeyi seç", style = MaterialTheme.typography.titleSmall)
            Text("Bu geçiş: düz çizgi · Önceki geçiş: kesik çizgi")
        }
        item { CurveComparisonMap(samples, index, onSelect = { index = it }) }
        item {
            Text("Viraj başlangıcından ${passNumber(point.distanceMeters)} m",
                modifier = Modifier.testTag("curve-pass-distance"))
            Slider(index.toFloat(), { index = it.roundToInt() },
                modifier = Modifier.semantics { contentDescription = "Viraj üzerindeki ortak mesafe" }
                    .testTag("curve-pass-distance-slider"),
                valueRange = 0f..samples.lastIndex.toFloat(), steps = (samples.size - 2).coerceAtMost(200))
        }
        item { CurvePassMetrics("Bu geçiş", selectedAt, comparison.selected, point.selectedSpeedKmh) }
        item { CurvePassMetrics("Önceki geçiş", previousAt, comparison.previous, point.previousSpeedKmh) }
    }
}

@Composable
private fun CurvePassMetrics(label: String, at: Long, pass: CurvePassSummary, speed: Double?) {
    StatisticsSurface {
        Text("$label · ${passDate(at)}", style = MaterialTheme.typography.titleMedium)
        Text("Seçilen noktada ${passSpeed(speed)}")
        Text("Süre ${passNumber(pass.durationMillis / 1000.0)} sn")
        Text("Örneklenen hız ${passSpeed(pass.sampledMinSpeedKmh)} – ${passSpeed(pass.sampledMaxSpeedKmh)}")
        val lean = pass.lean
        if (lean == null) Text("Yatış ölçümü yok")
        else {
            val mount = when (lean.mount) {
                LeanMount.HANDLEBAR -> "Gidon"
                LeanMount.BODY -> "Gövde"
                LeanMount.UNKNOWN -> "Montaj bilinmiyor"
            }
            Text("$mount · Tahmini tepe yatış")
            Text("Sol ${lean.leftDegrees?.let { "${passNumber(it)}°" } ?: "ölçüm yok"} · " +
                "Sağ ${lean.rightDegrees?.let { "${passNumber(it)}°" } ?: "ölçüm yok"}")
        }
    }
}

@Composable
private fun CurveComparisonMap(samples: List<CurvePassSample>, selected: Int, onSelect: (Int) -> Unit) {
    val frame = remember(samples) {
        val anchor = samples.first().selectedCoordinate
        val scale = cos(Math.toRadians(anchor.latitude)).coerceAtLeast(0.001)
        fun local(point: WeatherCoordinate): Offset {
            val longitude = (point.longitude - anchor.longitude + 540.0) % 360.0 - 180.0
            return Offset((longitude * scale).toFloat(), (anchor.latitude - point.latitude).toFloat())
        }
        CurveMapFrame(samples.map { local(it.selectedCoordinate) }, samples.map { local(it.previousCoordinate) })
    }
    val latestSelect by rememberUpdatedState(onSelect)
    val scheme = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(200.dp).testTag("curve-pass-map")
        .semantics { contentDescription = "Aynı virajdaki iki geçişin yerel haritası" }
        .pointerInput(frame) {
            detectTapGestures { tap ->
                val project = frame.project(size.width.toFloat(), size.height.toFloat())
                val index = frame.current.indices.minByOrNull { i ->
                    minOf((tap - project(frame.current[i])).getDistanceSquared(),
                        (tap - project(frame.prior[i])).getDistanceSquared())
                }
                index?.let(latestSelect)
            }
        }) {
        drawRect(scheme.surfaceContainer)
        val project = frame.project(size.width, size.height)
        fun path(points: List<Offset>) = Path().apply {
            val first = project(points.first())
            moveTo(first.x, first.y)
            points.drop(1).forEach { point -> project(point).let { lineTo(it.x, it.y) } }
        }
        drawPath(path(frame.current), scheme.primary, style = Stroke(width = 6.dp.toPx()))
        drawPath(path(frame.prior), scheme.tertiary, style = Stroke(width = 3.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))))
        drawCircle(scheme.primary, 7.dp.toPx(), project(frame.current[selected]))
        drawCircle(scheme.tertiary, 4.dp.toPx(), project(frame.prior[selected]))
    }
}

private class CurveMapFrame(val current: List<Offset>, val prior: List<Offset>) {
    private val all = current + prior
    private val minX = all.minOf { it.x }
    private val maxX = all.maxOf { it.x }
    private val minY = all.minOf { it.y }
    private val maxY = all.maxOf { it.y }

    fun project(width: Float, height: Float): (Offset) -> Offset {
        val factor = minOf(width * .85f / (maxX - minX).coerceAtLeast(.000001f),
            height * .85f / (maxY - minY).coerceAtLeast(.000001f))
        return { point -> Offset(width / 2 + (point.x - (minX + maxX) / 2) * factor,
            height / 2 + (point.y - (minY + maxY) / 2) * factor) }
    }
}

private val passLocale = Locale.forLanguageTag("tr-TR")
private fun passDate(at: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, passLocale).format(Date(at))
private fun passNumber(value: Double) = String.format(passLocale, "%.1f", value)
private fun passSpeed(speed: Double?) = speed?.let { "${passNumber(it)} km/sa" } ?: "hız verisi yok"
