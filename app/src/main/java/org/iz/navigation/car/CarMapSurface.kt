package org.iz.navigation.car

import android.app.Presentation
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.*
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.speed.roadSpeedPresentation
import kotlinx.coroutines.*
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
    private var generation = 0
    private var state = NavigationState()
    private var points = emptyList<TrackPoint>()
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trailJob: Job? = null
    private var trailRevision = 0L
    private var trailIdentity: String? = null
    private var recorded = RecordedTrailRender()
    private var paintedTrail: String? = null
    private var preview: PlannedRoute? = null
    private var following = true
    private var active = true
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

    override fun onStableAreaChanged(area: Rect) { stable = Rect(area); displayOutput { applyPadding() } }
    override fun onVisibleAreaChanged(area: Rect) { visible = Rect(area); displayOutput { applyPadding() } }
    override fun onScroll(distanceX: Float, distanceY: Float) {
        following = false
        displayOutput { map?.scrollBy(-distanceX, -distanceY, 0) }
    }
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor.isFinite() && scaleFactor > 0) {
            displayOutput { map?.moveCamera(CameraUpdateFactory.zoomBy(ln(scaleFactor.toDouble()) / ln(2.0))) }
        }
    }
    fun recenter() { following = true; displayOutput { render() } }
    fun zoom(amount: Double) { displayOutput { map?.moveCamera(CameraUpdateFactory.zoomBy(amount)) } }
    fun nightChanged() { displayOutput { applyStyle() } }
    fun lowMemory() { displayOutput { view?.onLowMemory() } }
    fun setActive(value: Boolean) {
        active = value
        view?.let {
            if (value && !resumed) { it.onResume(); resumed = true }
            if (!value && resumed) { it.onPause(); resumed = false }
        }
    }

    fun update(value: NavigationState, trail: List<TrackPoint>) {
        state = value
        val identity = value.journey?.id.takeIf { value.recording }
        val selected = if (identity == null) emptyList() else trail.filter { it.journeyId == identity }
        if (identity != trailIdentity || selected != points) {
            if (identity != trailIdentity || selected.isEmpty()) recorded = RecordedTrailRender()
            trailIdentity = identity
            points = selected
            val revision = ++trailRevision
            trailJob?.cancel()
            if (selected.isNotEmpty()) trailJob = renderScope.launch {
                val ready = withContext(Dispatchers.Default) {
                    val coroutine = currentCoroutineContext()
                    val result = recordedSpeedTrail(selected) { coroutine.ensureActive() }
                    RecordedTrailRender(result, result.geoJson(), setOfNotNull(identity))
                }
                if (revision == trailRevision) { recorded = ready; displayOutput { render() } }
            }
        }
        displayOutput { render() }
    }

    fun preview(route: PlannedRoute?) {
        preview = route
        following = route == null
        displayOutput { render(); framePreview() }
    }

    private fun framePreview() {
        val route = preview
        if (route != null) {
            val vertices = route.vertices.map { LatLng(it.coordinate.latitude, it.coordinate.longitude) }.distinct()
            if (vertices.size > 1) map?.moveCamera(CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.Builder().includes(vertices).build(), 48))
        }
    }

    private fun applyStyle() {
        val current = map ?: return
        val token = generation
        val dark = context.isDarkMode
        val json = JSONObject(mapStyle(OsmServiceSettings(context).read().tileUrl,
            listOf("car-route", "car-trail", "car-fix"), """
            {"id":"route","type":"line","source":"car-route","paint":{"line-color":"#3377ee","line-width":7}},
            {"id":"trail","type":"line","source":"car-trail","layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":["to-color",["get","color"]],"line-width":5}},
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
            if (token == generation) displayOutput { paintedTrail = null; applyPadding(); render(); framePreview() }
        }
    }

    private fun applyPadding() {
        val safe = Rect(0, 0, width, height)
        if (!stable.isEmpty) safe.intersect(stable)
        if (!visible.isEmpty) safe.intersect(visible)
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

    private fun render() {
        val current = map ?: return
        val route = preview ?: state.route
        current.updateGeoJson("car-route", featureCollection(route?.vertices?.takeIf { it.size > 1 }?.let {
            listOf(line(it.map { vertex -> vertex.coordinate.latitude to vertex.coordinate.longitude }))
        } ?: emptyList()))
        if (paintedTrail !== recorded.json) {
            current.updateGeoJson("car-trail", recorded.json)
            paintedTrail = recorded.json
        }
        speedPanel?.visibility = if (state.roadSpeed.visible && preview == null) View.VISIBLE else View.GONE
        val speed = roadSpeedPresentation(state.roadSpeed)
        ownSpeedLabel?.text = "Hızım\n${speed.ownText} km/sa"
        speedLimitLabel?.text = "Hız sınırı\n${speed.limitText} km/sa\n${speed.sourceText}"
        trailLegend?.text = (if (recorded.trail.scales.isNotEmpty()) "Mavi → Kırmızı\n" else "") + speedScaleLabel(recorded.trail)
        trailLegend?.visibility = if (recorded.trail.lines.isEmpty()) View.GONE else View.VISIBLE
        val fix = state.fix
        current.updateGeoJson("car-fix", featureCollection(if (fix != null) listOf(
            pointFeature(fix.coordinate.latitude, fix.coordinate.longitude)) else emptyList()))
        if (following && fix != null) current.moveCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(fix.coordinate.latitude, fix.coordinate.longitude))
                .zoom(if (current.cameraPosition.zoom < 10) 15.5 else current.cameraPosition.zoom)
                .bearing(if (state.guidance) fix.bearingDegrees?.toDouble() ?: 0.0 else 0.0).build()))
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
        paintedTrail = null
    }

    fun close() { trailJob?.cancel(); renderScope.cancel(); release(); context.unregisterComponentCallbacks(memoryCallbacks) }
}
