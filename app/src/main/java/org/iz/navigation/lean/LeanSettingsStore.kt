package org.iz.navigation.lean

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

internal data class LeanCaptureSettings(val enabled: Boolean = false, val mount: LeanMount = LeanMount.UNKNOWN,
    val calibration: LeanCalibration? = null)

internal fun validLeanReference(reference: LeanCalibration?): Boolean = reference != null &&
    reference.bodyConfirmed && reference.epoch.isNotBlank() && reference.createdAt > 0 &&
    reference.uprightGravity.size == 3 && reference.gyroBias.size == 3 &&
    reference.uprightGravity.all { it.isFinite() } && reference.gyroBias.all { it.isFinite() } &&
    sqrt(reference.uprightGravity.sumOf { it * it }) in 8.8..10.8

internal fun leanCaptureEligible(journey: Journey?, settings: LeanCaptureSettings): Boolean =
    settings.enabled && settings.mount == LeanMount.BODY && validLeanReference(settings.calibration) &&
        journey?.transport == Transport.MOTORCYCLE && journey.status == JourneyStatus.CONFIRMED && journey.endedAt == null

/** Explicit single-MOTO feature settings; not a transport/profile or a diary inference. */
internal class LeanSettingsStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lean_capture_v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private fun read(): LeanCaptureSettings = runCatching {
        val mount = LeanMount.valueOf(prefs.getString("mount", LeanMount.UNKNOWN.name)!!)
        val reference = prefs.getString("reference", null)?.let { encoded ->
            val json = JSONObject(encoded)
            fun vector(name: String) = json.getJSONArray(name).let { values ->
                require(values.length() == 3); (0..2).map { values.getDouble(it) }
            }
            LeanCalibration(json.getString("epoch"), json.getLong("created"), vector("gravity"), vector("bias"),
                json.getBoolean("body"))
        }?.takeIf(::validLeanReference)
        LeanCaptureSettings(prefs.getBoolean("enabled", false), mount, reference)
    }.getOrElse { LeanCaptureSettings() }
    @Synchronized fun enabled(value: Boolean) = save(state.value.copy(enabled = value))
    @Synchronized fun mount(value: LeanMount) = save(state.value.copy(mount = value, calibration = null))
    @Synchronized fun clearCalibration() = save(state.value.copy(calibration = null))
    @Synchronized fun reference(value: LeanCalibration) {
        require(state.value.mount == LeanMount.BODY && validLeanReference(value))
        save(state.value.copy(calibration = value))
    }
    private fun save(value: LeanCaptureSettings) {
        val reference = value.calibration?.let { JSONObject().put("epoch", it.epoch).put("created", it.createdAt)
            .put("gravity", JSONArray(it.uprightGravity)).put("bias", JSONArray(it.gyroBias)).put("body", it.bodyConfirmed).toString() }
        check(prefs.edit().putBoolean("enabled", value.enabled).putString("mount", value.mount.name)
            .putString("reference", reference).commit()) { "Motosiklet ölçüm ayarları kaydedilemedi." }
        mutable.value = value
    }
    companion object {
        @Volatile private var instance: LeanSettingsStore? = null
        fun get(context: Context): LeanSettingsStore = instance ?: synchronized(this) {
            instance ?: LeanSettingsStore(context).also { instance = it }
        }
    }
}