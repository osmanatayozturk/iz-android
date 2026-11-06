package org.iz.navigation.integration

import org.iz.navigation.navigation.DriveCameraTarget
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/** One map's driving transition, called on the map's main thread. */
internal class DriveCameraAnimator(
    private val startEase: (DriveCameraTarget, Int, () -> Unit) -> Unit,
    private val cancelEase: () -> Unit,
) {
    constructor(map: MapLibreMap) : this({ target, durationMillis, ended ->
        val position = CameraPosition.Builder()
            .target(LatLng(target.coordinate.latitude, target.coordinate.longitude))
            .zoom(target.zoom)
            .bearing(target.bearing)
            .tilt(target.pitch)
            .padding(target.padding.toDoubleArray())
            .build()
        map.easeCamera(CameraUpdateFactory.newCameraPosition(position), durationMillis,
            object : MapLibreMap.CancelableCallback {
                override fun onFinish() = ended()
                override fun onCancel() = ended()
            })
    }, map::cancelTransitions)

    private var active: Any? = null

    fun ease(target: DriveCameraTarget) {
        cancel()
        val token = Any()
        active = token
        try {
            startEase(target, 500) { if (active === token) active = null }
        } catch (error: Exception) {
            if (active === token) active = null
            throw error
        }
    }

    fun cancel(): Boolean {
        if (active == null) return false
        // Native cancellation may deliver an old callback after the next ease starts.
        active = null
        cancelEase()
        return true
    }
}
