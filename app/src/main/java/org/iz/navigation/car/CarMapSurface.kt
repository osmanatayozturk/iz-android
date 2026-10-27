package org.iz.navigation.car

import android.app.Presentation
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import org.iz.navigation.integration.*
import org.iz.navigation.navigation.*
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.speed.roadSpeedPresentation
import org.iz.navigation.power.PowerDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import kotlin.math.ln

/** Host Surface -> VirtualDisplay -> Presentation -> an independent MapLibre texture MapView.
 * See developer.android.com/training/cars/apps/library/draw-maps. No screenshots/phone View reuse.
 */
internal class CarMapSurface(private val context: CarContext) : SurfaceCallback {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var surface: Surface? = null
    private var attribution: TextView? = null
    private var speedPanel: LinearLayout? = null
    private var ownSpeedLabel: TextView? = null
    private var speedLimitLabel: TextView? = null
    private var trailLegend: TextView? = null
    private var width = 0
    private var height = 0
    private var stable = Rect()
    private var visible = Rect()
    private var stableKnown = false
    private var visibleKnown = false
    private var generation = 0
    private var state = NavigationState()
    private var preparedRoute: PlannedRoute? = null
    private var hasPreparedRoute = false
    private var routeJson = emptyGeoJson
    private var paintedRouteJson: String? = null
    private var paintedFixJson: String? = null
    private var preview: PlannedRoute? = null
    private val interaction = CarCameraInteraction()
    private val cameraPolicy = DriveCameraPolicy()
    private var drivingCamera = false
    private var adaptiveAreaSafe = false
    private var legacyCameraAt = Long.MIN_VALUE
    private var legacyFix: NavigationFix? = null
    private var active = false
    private var resumed = false
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() { lowMemory() }
        override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) lowMemory() }
    }
    init { context.registerComponentCallbacks(memoryCallbacks) }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        release()
        val hostSurface = container.surface ?: return
        if (!hostSurface.isValid || container.width <= 0 || container.height <= 0 || container.dpi <= 0) return
        width = container.width
        height = container.height
        stable = Rect(); visible = Rect(); stableKnown = false; visibleKnown = false
        surface = hostSurface
        val token = generation
        try {
            OsmMapRuntime.initialize(context)
            display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "İz Android Auto map", width, height, container.dpi, hostSurface, 0)
            val p = Presentation(context, checkNotNull(display).display)
            presentation = p
            val mapView = MapView(p.context, MapLibreMapOptions.createFromAttributes(p.context)
                .textureMode(true).setPrefetchZoomDelta(0).attributionEnabled(false).logoEnabled(false)
                .camera(CameraPosition.Builder().target(LatLng(39.0, 35.0)).zoom(4.5).build()))
            view = mapView
            mapView.setMaximumFps(30)
            mapView.onCreate(null)
            val frame = FrameLayout(p.context)
            frame.addView(mapView, FrameLayout.LayoutParams(-1, -1))
            attribution = TextView(p.context).apply {
                text = "© OpenStreetMap contributors"
                textSize = 11f
                setPadding(8, 3, 8, 3)
                setTextColor(Color.BLACK)
                setBackgroundColor(0xeefafafa.toInt())
                addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    if (bottom - top != oldBottom - oldTop) displayOutput { applyPadding() }
                }
            }.also { frame.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT)) }
            speedPanel = LinearLayout(p.context).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                fun valueLabel(): TextView = TextView(p.context).apply {
                    textSize = 17f
                    setPadding(12, 8, 12, 8)
                    setTextColor(Color.BLACK)
                    setBackgroundColor(0xeefafafa.toInt())
                }
                ownSpeedLabel = valueLabel().also { addView(it) }
                speedLimitLabel = valueLabel().also { addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 8 }) }
            }.also { frame.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT)) }
            speedPanel?.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    displayOutput { render() }
                }
            }
            trailLegend = TextView(p.context).apply {
                textSize = 11f
                setPadding(8, 3, 8, 3)
                setTextColor(Color.BLACK)
                setBackgroundColor(0xeefafafa.toInt())
                visibility = View.GONE
            }.also { frame.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT)) }
            p.setContentView(frame)
            p.show()
            mapView.onStart()
            if (active) { mapView.onResume(); resumed = true }
            mapView.getMapAsync { ready ->
                if (token != generation) return@getMapAsync
                displayOutput {
                    map = ready
                    ready.setMaxZoomPreference(19.0)
                    ready.setPrefetchZoomDelta(0)
                    applyStyle()
                }
            }
        } catch (failure: RuntimeException) {
            release()
            recordDisplayFailure()
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        if (surface == container.surface) release()
    }

    override fun onStableAreaChanged(area: Rect) {
        // AndroidX defines an empty host area as unknown, not as a hidden surface.
        stable = Rect(area); stableKnown = !area.isEmpty
        displayOutput { applyPadding(); syncVisibility() }
    }
    override fun onVisibleAreaChanged(area: Rect) {
        visible = Rect(area); visibleKnown = !area.isEmpty
        displayOutput { applyPadding(); syncVisibility() }
    }
    override fun onScroll(distanceX: Float, distanceY: Float) {
        interaction.manual()
        displayOutput { if (active && resumed) map?.scrollBy(-distanceX, -distanceY, 0) }
    }
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor.isFinite() && scaleFactor > 0) {
            interaction.manual()
            displayOutput { if (active && resumed) map?.moveCamera(CameraUpdateFactory.zoomBy(ln(scaleFactor.toDouble()) / ln(2.0))) }
        }
    }
    fun recenter() { interaction.recenter(); displayOutput { render(forceCamera = true) } }
    fun panMode(enabled: Boolean) { if (enabled) interaction.manual() }
    fun zoom(amount: Double) {
        interaction.manual()
        displayOutput { if (active && resumed) map?.moveCamera(CameraUpdateFactory.zoomBy(amount)) }
    }
    val cameraMode get() = interaction.mode
    fun cameraMode(value: DriveCameraMode) {
        interaction.setMode(value)
        displayOutput {
            if (!interaction.following) flatCamera()
            render(forceCamera = true)
        }
    }
    val overviewAvailable get() = (preview ?: state.route)?.vertices?.size?.let { it > 1 } == true
    fun overview() { displayOutput { framePreview(hold = true) } }
    fun nightChanged() { displayOutput { applyStyle() } }
    fun lowMemory() { displayOutput { view?.onLowMemory() } }
    fun setActive(value: Boolean) {
        active = value
        syncVisibility()
    }
    private fun syncVisibility() {
        val shown = active && !safeArea().isEmpty
        view?.let {
            if (shown && !resumed) { it.onResume(); resumed = true }
            if (!shown && resumed) { it.onPause(); resumed = false }
        }
        if (shown) displayOutput { render(forceCamera = true) }
    }

    fun update(value: NavigationState) {
        state = value
        displayOutput { render() }
    }

    fun preview(route: PlannedRoute?) {
        preview = route
        interaction.preview(route != null)
        displayOutput { if (route != null) flatCamera(); render(forceCamera = true); if (route != null) framePreview() }
    }

    private fun framePreview(hold: Boolean = false) {
        if (!active || !resumed) return
        val route = preview ?: state.route ?: return
        val current = map ?: return
        val vertices = route.vertices.map { LatLng(it.coordinate.latitude, it.coordinate.longitude) }.distinct()
        if (vertices.size < 2) return
        // Recheck the live route before changing follow state: the menu may already be stale.
        if (hold) { interaction.manual(); flatCamera() }
        current.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(vertices).build(), 48))
    }

    private fun applyStyle() {
        val current = map ?: return
        val token = generation
        val dark = context.isDarkMode
        val json = JSONObject(mapStyle(OsmServiceSettings(context).read().tileUrl,
            listOf("car-route", "car-fix"), """
            {"id":"route","type":"line","source":"car-route","paint":{"line-color":"#3377ee","line-width":7}},
            {"id":"fix","type":"circle","source":"car-fix","paint":{"circle-color":"#00cbaa","circle-radius":9,"circle-stroke-color":"#ffffff","circle-stroke-width":3}}
            """))
        if (dark) json.getJSONArray("layers").getJSONObject(0).put("paint", JSONObject()
            .put("raster-brightness-max", 0.35).put("raster-saturation", -0.6))
        attribution?.setTextColor(if (dark) Color.WHITE else Color.BLACK)
        attribution?.setBackgroundColor(if (dark) 0xee20252c.toInt() else 0xeefafafa.toInt())
        listOfNotNull(ownSpeedLabel, speedLimitLabel, trailLegend).forEach {
            it.setTextColor(if (dark) Color.WHITE else Color.BLACK)
            it.setBackgroundColor(if (dark) 0xee20252c.toInt() else 0xeefafafa.toInt())
        }
        current.setStyle(Style.Builder().fromJson(json.toString())) {
            if (token == generation) displayOutput {
                paintedRouteJson = null; paintedFixJson = null; legacyFix = null
                applyPadding(); render(forceCamera = true)
                if (preview != null) framePreview()
            }
        }
    }

    private fun applyPadding() {
        val safe = safeArea()
        if (safe.isEmpty) return
        map?.setPadding(safe.left, safe.top, width - safe.right, height - safe.bottom)
        attribution?.let { label ->
            label.maxWidth = (safe.width() - 16).coerceAtLeast(1)
            label.layoutParams = (label.layoutParams as FrameLayout.LayoutParams).apply {
                rightMargin = this@CarMapSurface.width - safe.right + 8
                bottomMargin = this@CarMapSurface.height - safe.bottom + 8
            }
        }
        speedPanel?.let { panel ->
            val available = (safe.width() - 32).coerceAtLeast(1)
            ownSpeedLabel?.maxWidth = (available * .3).toInt().coerceAtLeast(1)
            speedLimitLabel?.maxWidth = (available * .7).toInt().coerceAtLeast(1)
            panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = safe.left + 12; topMargin = safe.top + 12
            }
        }
        trailLegend?.let { label ->
            val attributionHeight = attribution?.let {
                it.height.coerceAtLeast(it.lineHeight + it.paddingTop + it.paddingBottom)
            } ?: 0
            label.maxWidth = (safe.width() * .65).toInt().coerceAtLeast(1)
            label.layoutParams = (label.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = safe.left + 8
                bottomMargin = this@CarMapSurface.height - safe.bottom + attributionHeight + 16
            }
        }
    }

    private fun line(coordinates: List<Pair<Double, Double>>): JSONObject = JSONObject()
        .put("type", "Feature").put("properties", JSONObject()).put("geometry", JSONObject()
            .put("type", "LineString").put("coordinates", JSONArray().apply {
                coordinates.forEach { (latitude, longitude) -> put(JSONArray().put(longitude).put(latitude)) }
            }))

    private fun safeArea(): Rect {
        val safe = Rect(0, 0, width, height)
        if (stableKnown && (stable.isEmpty || !safe.intersect(stable))) return Rect()
        if (visibleKnown && (visible.isEmpty || !safe.intersect(visible))) return Rect()
        return safe
    }

    private fun cameraViewport(): DriveCameraViewport {
        val safe = safeArea()
        val speedHeight = speedPanel?.takeIf { it.visibility == View.VISIBLE }?.let { panel ->
            maxOf(panel.height, speedLimitLabel?.let { it.lineHeight * 3 + it.paddingTop + it.paddingBottom } ?: 0,
                ownSpeedLabel?.let { it.lineHeight * 2 + it.paddingTop + it.paddingBottom } ?: 0)
        } ?: 0
        val attributionHeight = attribution?.let { it.height.coerceAtLeast(it.lineHeight + it.paddingTop + it.paddingBottom) } ?: 0
        return DriveCameraViewport(width, height, safe.left, safe.top + if (speedHeight > 0) speedHeight + 12 else 0,
            width - safe.right, height - safe.bottom + attributionHeight + 8,
            known = stableKnown && visibleKnown && !safe.isEmpty)
    }

    private fun flatCamera() {
        val current = map ?: return
        if (!active || !resumed) return
        val safe = safeArea()
        if (safe.isEmpty) return
        val viewport = cameraViewport()
        val insets = if (viewport.leftPx.toLong() + viewport.rightPx < width &&
            viewport.topPx.toLong() + viewport.bottomPx < height) doubleArrayOf(viewport.leftPx.toDouble(),
                viewport.topPx.toDouble(), viewport.rightPx.toDouble(), viewport.bottomPx.toDouble())
            else doubleArrayOf(safe.left.toDouble(), safe.top.toDouble(), (width - safe.right).toDouble(), (height - safe.bottom).toDouble())
        val position = current.cameraPosition
        if (position.tilt == 0.0 && position.bearing == 0.0 && position.padding?.contentEquals(insets) == true) return
        current.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(position)
            .tilt(0.0).bearing(0.0).padding(insets).build()))
    }

    private fun render(forceCamera: Boolean = false) {
        if (!active || !resumed || safeArea().isEmpty) return
        val current = map ?: return
        val route = preview ?: state.route
        if (!hasPreparedRoute || preparedRoute !== route) {
            preparedRoute = route
            hasPreparedRoute = true
            routeJson = featureCollection(route?.vertices?.takeIf { it.size > 1 }?.let {
                listOf(line(it.map { vertex -> vertex.coordinate.latitude to vertex.coordinate.longitude }))
            } ?: emptyList())
        }
        if (paintedRouteJson != routeJson) {
            current.updateMeasuredGeoJson("car-route", routeJson, PowerDiagnostics.Metric.MAP_ROUTE)
            paintedRouteJson = routeJson
        }
        speedPanel?.visibility = if (state.roadSpeed.visible && preview == null) View.VISIBLE else View.GONE
        val speed = roadSpeedPresentation(state.roadSpeed)
        ownSpeedLabel?.text = "Hızım\n${speed.ownText} km/sa"
        speedLimitLabel?.text = "Hız sınırı\n${speed.limitText} km/sa\n${speed.sourceText}"
        trailLegend?.visibility = View.GONE
        val fix = state.fix
        val fixJson = featureCollection(if (fix != null) listOf(
            pointFeature(fix.coordinate.latitude, fix.coordinate.longitude)) else emptyList())
        if (paintedFixJson != fixJson) {
            current.updateMeasuredGeoJson("car-fix", fixJson, PowerDiagnostics.Metric.MAP_POSITION)
            paintedFixJson = fixJson
        }
        val eligible = preview == null && carDriveCameraEnabled(state)
        if (drivingCamera && !eligible) { cameraPolicy.reset(); flatCamera() }
        drivingCamera = eligible
        val viewport = cameraViewport()
        val safeForAdaptive = viewport.hasSafeAdaptiveArea()
        val lostAdaptiveArea = adaptiveAreaSafe && !safeForAdaptive
        adaptiveAreaSafe = safeForAdaptive
        if (!safeForAdaptive) {
            // Safety transitions cannot depend on a fresh fix or on follow/manual state.
            // Preserve the actual native target/zoom, never recenter onto a stale fix.
            flatCamera()
            if (lostAdaptiveArea) return
        }
        val now = SystemClock.elapsedRealtime()
        if (eligible && interaction.following) {
            val progress = state.progress?.takeIf { state.guidance && !state.arrived && !it.offRoute && it.maneuverIndex >= 0 }
            val target = cameraPolicy.update(DriveCameraInput(now, fix?.coordinate, fix?.speedMps?.toDouble()?.times(3.6),
                fix?.bearingDegrees?.toDouble(), fresh = !state.gpsStale && fix != null, enabled = true,
                maneuverMeters = progress?.nextManeuverDistanceMeters,
                maneuverSeconds = state.route?.maneuvers?.getOrNull(progress?.maneuverIndex ?: -1)?.let { step ->
                    progress?.let { (step.beginElapsedSeconds - it.elapsedSeconds).coerceAtLeast(0.0) }
                }, mode = interaction.mode), viewport, forceCamera)
            if (target != null) current.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(current.cameraPosition)
                .target(LatLng(target.coordinate.latitude, target.coordinate.longitude)).zoom(target.zoom)
                .bearing(target.bearing).tilt(target.pitch).padding(target.padding.toDoubleArray()).build()))
        } else if (!eligible && preview == null && interaction.following && fix != null && !state.gpsStale &&
            (forceCamera || fix != legacyFix && (legacyCameraAt == Long.MIN_VALUE || now - legacyCameraAt >= 1000))) {
            current.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(current.cameraPosition)
                .target(LatLng(fix.coordinate.latitude, fix.coordinate.longitude))
                .zoom(if (current.cameraPosition.zoom < 10) 15.5 else current.cameraPosition.zoom).tilt(0.0).bearing(0.0).build()))
            legacyFix = fix; legacyCameraAt = now
        }
    }

    private inline fun displayOutput(block: () -> Unit) {
        if (!safelyUpdateCarDisplay(block)) recordDisplayFailure()
    }

    private fun recordDisplayFailure() {
        org.iz.navigation.tracking.LocationDiagnostics(java.io.File(context.noBackupFilesDir, "diagnostics"))
            .record(org.iz.navigation.tracking.LocationDiagnostics.Event.DISPLAY_FAILED, null, null)
    }

    fun release() {
        generation++
        map = null
        view?.let { runCatching { if (resumed) it.onPause(); it.onStop(); it.onDestroy() } }
        resumed = false
        view = null
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { display?.release() }
        display = null
        surface = null
        attribution = null
        speedPanel = null; ownSpeedLabel = null; speedLimitLabel = null; trailLegend = null
        paintedRouteJson = null; paintedFixJson = null
        cameraPolicy.reset(); drivingCamera = false; adaptiveAreaSafe = false; legacyFix = null; legacyCameraAt = Long.MIN_VALUE
    }

    fun close() { release(); context.unregisterComponentCallbacks(memoryCallbacks) }
}
