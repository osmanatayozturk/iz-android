package org.iz.navigation.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.iz.navigation.lean.HandlebarCalibrationResult
import org.iz.navigation.lean.HandlebarCalibrationStep
import org.iz.navigation.lean.LeanCalibration

@Composable
internal fun HandlebarCalibrationControl(
    hasProfile: Boolean,
    canCalibrate: () -> Boolean,
    onSave: (LeanCalibration) -> Unit,
    onReset: () -> Unit,
    capture: suspend (
        (HandlebarCalibrationStep) -> Unit, () -> Unit, suspend (HandlebarCalibrationStep) -> Unit,
    ) -> HandlebarCalibrationResult,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val opener = remember { FocusRequester() }
    val cancelButton = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    val parkButton = remember { FocusRequester() }
    val startButton = remember { FocusRequester() }
    val retryButton = remember { FocusRequester() }
    val currentAllowed by rememberUpdatedState(canCalibrate)
    val currentCapture by rememberUpdatedState(capture)
    var open by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var parked by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var step by remember { mutableStateOf<HandlebarCalibrationStep?>(null) }
    var stepAccepted by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var result by remember { mutableStateOf<LeanCalibration?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableLongStateOf(0L) }
    fun close() {
        attempt++
        job?.cancel()
        job = null; result = null; step = null; parked = false; error = null
        retry = null; stepAccepted = false
        if (open) { open = false; restoreFocus = true }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> { resumed = false; close() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); attempt++; job?.cancel() }
    }
    LaunchedEffect(open, resumed, restoreFocus) {
        if (!open && resumed && restoreFocus) {
            opener.requestFocus()
            restoreFocus = false
        }
    }
    Text("Gidon yatışı deneysel bir sürüş sonrası tahmindir.")
    Button(onClick = { open = true }, modifier = Modifier.focusRequester(opener)
        .heightIn(min = 48.dp).testTag("handlebar-calibration-open")) {
        Text(if (hasProfile) "Gidonu yeniden kalibre et" else "Gidonu kalibre et")
    }
    if (hasProfile) {
        Text("Gidon kalibrasyonu hazır.")
        TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Bağlantı değişti · Kalibrasyonu sıfırla")
        }
    }
    if (!open) return

    Dialog(onDismissRequest = ::close) {
        val view = LocalView.current
        LaunchedEffect(job != null, retry, result) {
            when {
                result != null -> saveButton.requestFocus()
                retry != null -> retryButton.requestFocus()
                else -> cancelButton.requestFocus()
            }
        }
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Gidon kalibrasyonu", style = MaterialTheme.typography.titleLarge)
                Text("Güvenli ve düz bir yerde dur. Motoru kapat, motosikleti dik ve sabit tut. Telefon gidona sıkıca sabit olmalı.")
                if (job == null && result == null) {
                    Text("Sinyallerle gidonu düz tut, sola çevir, sağa çevir ve yeniden düz konuma getir. Her konumda bir sonraki sinyali bekle.")
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("handlebar-calibration-parked")
                        .focusRequester(parkButton).focusProperties { down = if (parked) startButton else cancelButton }
                        .toggleable(parked, role = Role.Checkbox, onValueChange = { parked = it }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(parked, onCheckedChange = null)
                        Text("Park halindeyim ve motosiklet dik.", Modifier.weight(1f))
                    }
                    Button(enabled = parked && resumed, modifier = Modifier.fillMaxWidth()
                        .focusRequester(startButton).focusProperties { up = parkButton; down = cancelButton }
                        .heightIn(min = 48.dp).testTag("handlebar-calibration-start"), onClick = {
                        if (job == null && parked && resumed && currentAllowed()) {
                            error = null
                            val owner = ++attempt
                            val captureJob = scope.launch(start = CoroutineStart.LAZY) {
                                val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 75) }.getOrNull()
                                fun cue(tone: Int) {
                                    runCatching { tones?.startTone(tone, 180) }
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                try {
                                    fun checkOwner() {
                                        if (owner != attempt || !resumed || !currentAllowed()) {
                                            throw CancellationException("Calibration screen changed")
                                        }
                                    }
                                    val measured = currentCapture({ next ->
                                        checkOwner()
                                        step = next
                                        stepAccepted = false
                                        cue(next.tone)
                                    }, {
                                        checkOwner()
                                        stepAccepted = true
                                        cue(ToneGenerator.TONE_PROP_ACK)
                                    }, { failed ->
                                        checkOwner()
                                        step = failed
                                        stepAccepted = false
                                        cue(ToneGenerator.TONE_PROP_NACK)
                                        val request = CompletableDeferred<Unit>()
                                        retry = request
                                        request.await()
                                        retry = null
                                        step = null
                                    })
                                    ensureActive()
                                    if (owner != attempt || !resumed || !currentAllowed()) return@launch
                                    when (measured) {
                                        is HandlebarCalibrationResult.Ready -> {
                                            result = measured.profile
                                        }
                                        HandlebarCalibrationResult.SensorsUnavailable -> error = "İvmeölçer ve jiroskop gerekli."
                                        HandlebarCalibrationResult.InvalidSamples -> {
                                            error = "Hareketler doğrulanamadı. Gidonu düz konuma getirip yeniden dene."
                                            cue(ToneGenerator.TONE_PROP_NACK)
                                            delay(200)
                                        }
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { if (owner == attempt) error = "Kalibrasyon tamamlanamadı. Yeniden deneyebilirsin." }
                                finally {
                                    tones?.release()
                                    if (owner == attempt) { job = null; step = null; retry = null; stepAccepted = false }
                                }
                            }
                            job = captureJob
                            captureJob.start()
                        }
                    }) { Text(if (error == null) "Kalibrasyonu başlat" else "Yeniden dene") }
                }
                val retryRequest = retry
                if (retryRequest != null) {
                    Text(step?.retryInstruction.orEmpty(), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error)
                    Button(onClick = { retryRequest.complete(Unit) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .focusRequester(retryButton).testTag("handlebar-calibration-retry")) { Text("Bu adımı yeniden dene") }
                } else if (job != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    val instruction = if (stepAccepted) "Adım doğrulandı. Bir sonraki sinyali bekle."
                        else step?.instruction ?: "Sinyali bekle…"
                    Text(instruction, Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }.testTag("handlebar-calibration-step"))
                }
                error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error) }
                result?.let { profile ->
                    Text("Gidon hareketleri doğrulandı. Kalibrasyonu kaydedebilirsin.")
                    Button(onClick = {
                        if (result === profile && resumed && currentAllowed()) {
                            try { onSave(profile); close() }
                            catch (_: Exception) { error = "Kalibrasyon kaydedilemedi. Yeniden deneyebilirsin." }
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .focusRequester(saveButton).testTag("handlebar-calibration-save")) { Text("Kalibrasyonu kaydet") }
                }
                TextButton(onClick = ::close, modifier = Modifier.heightIn(min = 48.dp)
                    .focusRequester(cancelButton).focusProperties {
                        up = when {
                            result != null -> saveButton
                            retry != null -> retryButton
                            job != null -> FocusRequester.Default
                            parked -> startButton
                            else -> parkButton
                        }
                    }.testTag("handlebar-calibration-cancel")) { Text("İptal") }
            }
        }
    }
}

private val HandlebarCalibrationStep.instruction: String
    get() = when (this) {
        HandlebarCalibrationStep.STRAIGHT -> "1 / 4 · Gidonu düz tut."
        HandlebarCalibrationStep.LEFT -> "2 / 4 · Gidonu sola çevir ve sabit tut."
        HandlebarCalibrationStep.RIGHT -> "3 / 4 · Gidonu sağa çevir ve sabit tut."
        HandlebarCalibrationStep.CENTER -> "4 / 4 · Gidonu düz konuma getir ve sabit tut."
    }

private val HandlebarCalibrationStep.tone: Int
    get() = when (this) {
        HandlebarCalibrationStep.STRAIGHT -> ToneGenerator.TONE_PROP_BEEP
        HandlebarCalibrationStep.LEFT -> ToneGenerator.TONE_DTMF_1
        HandlebarCalibrationStep.RIGHT -> ToneGenerator.TONE_DTMF_3
        HandlebarCalibrationStep.CENTER -> ToneGenerator.TONE_DTMF_5
    }

private val HandlebarCalibrationStep.retryInstruction: String
    get() = when (this) {
        HandlebarCalibrationStep.STRAIGHT -> "Düz bekleme doğrulanamadı. Gidonu düz konuma getir ve motosikleti sabit tut."
        HandlebarCalibrationStep.LEFT -> "Sol dönüş doğrulanamadı. Gidonu düz konuma getir. Sinyalden sonra yeniden sola çevir."
        HandlebarCalibrationStep.RIGHT -> "Sağ dönüş doğrulanamadı. Gidonu önceki sol konuma getir. Sinyalden sonra yeniden sağa çevir."
        HandlebarCalibrationStep.CENTER -> "Düze dönüş doğrulanamadı. Gidonu önceki sağ konuma getir. Sinyalden sonra yeniden düz konuma getir."
    }
