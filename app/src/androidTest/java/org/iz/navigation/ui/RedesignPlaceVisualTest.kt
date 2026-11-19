package org.iz.navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import org.iz.navigation.car.SharedMapInput
import org.iz.navigation.car.SharedMapStop
import org.iz.navigation.data.*
import org.iz.navigation.integration.MapPlaceCandidate
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.placeinfo.*
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class RedesignPlaceVisualTest {
    @get:Rule val compose = createComposeRule()
    private val instant = Instant.parse("2026-11-12T12:00:00Z")
    private val place = Place(id = "visual-place", name = "Örnek kütüphane")
    private val visit = Visit(id = "visual-visit", placeId = place.id, visitedAt = instant.toEpochMilli(),
        note = "Okuma salonunda kısa bir mola.")
    private val ref = OsmRef(OsmType.NODE, 1001)
    private val publicKey = PlaceInfoKey("https://example.invalid/api/0.6", ref)
    private val hours = "Mo-Fr 09:00-18:00; Sa 10:00-16:00"
    private val snapshot = PublicPlaceSnapshot(publicKey, 1, mapOf("name" to place.name,
        "amenity" to "library", "opening_hours" to hours, "timezone" to "Europe/Istanbul",
        "wheelchair" to "yes"), instant.toEpochMilli())

    @Test fun captureAllocatedPlacePages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ids = InstrumentationRegistry.getArguments().getString("izVisualPlaceIds")
            ?.split(",")?.filter(String::isNotBlank)?.distinct().orEmpty()
        assumeTrue(ids.isNotEmpty())
        check(context.packageName == "org.iz.navigation.qaredesign20261119")
        check(context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
        require(ids.all { it in setOf("links", "sharedmode", "near", "public", "hoursview",
            "place", "placeedit", "visit", "photoshare", "shortcut", "journaledit", "offline", "break", "pick") })
        val out = File(requireNotNull(context.getExternalFilesDir(null)), "qa/visual")
        check(out.isDirectory || out.mkdirs())
        val picture = if ("photoshare" in ids) {
            val photos = File(context.filesDir, "photos")
            check(photos.isDirectory || photos.mkdirs())
            File.createTempFile("visual-qa-", ".png", photos)
        } else null
        try {
            picture?.let { file ->
                val bitmap = Bitmap.createBitmap(160, 96, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.rgb(75, 111, 91))
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
            val photo = picture?.let { Photo(relativePath = "photos/" + it.name, caption = "Örnek fotoğraf") }
            val current = mutableStateOf(ids.first())
            compose.setContent { IzTheme { Surface(Modifier.fillMaxSize()) {
                Box { key(current.value) { Page(current.value, photo) } }
            } } }
            for (id in ids) {
                compose.runOnIdle { current.value = id }
                compose.waitForIdle()
                if (id == "break") compose.onNodeWithText("Mola ekle").performClick()
                if (id == "photoshare") {
                    compose.onNode(isToggleable()).performScrollTo().performClick()
                    compose.onNodeWithText("Seçimi incele").performScrollTo().performClick()
                    compose.waitForIdle()
                }
                val dialog = compose.onAllNodes(isDialog()).fetchSemanticsNodes()
                val root = if (dialog.isEmpty()) compose.onRoot() else compose.onNode(isDialog())
                val bitmap = root.captureToImage().asAndroidBitmap()
                try {
                    File(out, "$id.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        } finally { picture?.delete() }
    }

    @Composable
    private fun Page(id: String, photo: Photo?) {
        when (id) {
            "shortcut" -> DestinationShortcutAssignmentScreen(DestinationShortcutKind.HOME,
                listOf(place.copy(latitude = 41.0, longitude = 29.0)), false, null, {}, {}, {}, {})
            "journaledit" -> JourneyEditor(Journey(title = "Örnek bisiklet gezisi", transport = Transport.BICYCLE,
                startedAt = instant.toEpochMilli() - 60_000, endedAt = instant.toEpochMilli()), {}, {})
            "offline" -> PreparedRoutePanel(PreparedRoutePanelData(
                token = PreparedRouteUiToken("visual", null, null, 1, 1, null), route = null,
                routeReady = false, mapState = PreparedMapUiState.SOURCE_UNAVAILABLE,
                corridor = null, allowedActions = emptySet()),
                PreparedRoutePanelCallbacks({ _, _ -> }, { _, _ -> }), Modifier.verticalScroll(rememberScrollState()))
            "break" -> PlannedBreakEditor(RouteStop(place.name, WeatherCoordinate(41.0, 29.0)), enabled = true,
                onMinutesChanged = {})
            "pick" -> WeatherStopMapPicker("Durak konumu", onDismiss = {}, onChoose = {})
            "links" -> SharedMapEntryDialog(SharedMapInput.Single(SharedMapStop(place.name)), {}, {}, { _, _ -> },
                resolve = { null })
            "sharedmode" -> QuickDirectionsDialog(emptyList(), remember { MutableStateFlow(NavigationState()) },
                locate = { error("Visual fixture") }, validateOrigin = {}, plan = { _, _, _, _ -> error("Visual fixture") },
                activate = { _, _, _ -> error("Visual fixture") }, onDismiss = {}, onStarted = {},
                hasLocationPermission = { false }, initialDestination = RouteStop(place.name, WeatherCoordinate(41.0, 29.0)),
                sharedPreview = true)
            "near" -> {
                val candidate = MapPlaceCandidate(SelectedOsmPlace(place.name, 41.0, 29.0, ref), "Kütüphane", 18.0)
                val building = MapPlaceCandidate(SelectedOsmPlace("Kütüphane binası", 41.0001, 29.0001,
                    OsmRef(OsmType.WAY, 1002)), "Bina", 24.0)
                PlaceInformationSelectionPanel(PlaceInformationDiscoveryState(open = true,
                    candidates = listOf(candidate, building), selectedRef = ref), {}, {}, {}, {}, {})
            }
            "public" -> PlaceInformationMapDialog({}) {
                PlaceInformationContent(PlaceInfoState(key = publicKey, snapshot = snapshot), publicKey, place.name,
                    instant, ZoneId.of("UTC"), null, {}, {}, {}, {}, {}, {}, { _, _ -> }, onClose = {},
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), onSave = {})
            }
            "hoursview" -> {
                val zone = PlaceInfoZones.resolve(null, "Europe/Istanbul", ZoneId.of("UTC"))
                PlaceInformationHoursDetail(BasicPlaceOpeningHours.evaluate(hours, instant, zone.zone), zone, onClose = {})
            }
            "place" -> PlaceDetail(place, DiaryState(places = listOf(place), visits = listOf(visit)),
                onEdit = {}, onMaps = {}, onContribute = {}, onCorrect = null, onAddVisit = {}, onEditVisit = {},
                onShare = {}, onPhoto = {}, onDelete = {}, onDeleteVisit = {}, onDeletePhoto = {}, onEditPhoto = {})
            "placeedit" -> PlaceDetailsEditor(place, null, {}, {}, {})
            "visit" -> VisitEditor(visit, onDismiss = {}, placeName = place.name, onPhoto = {}, onSave = {})
            "photoshare" -> PhotoShareSheet(listOf(requireNotNull(photo)), {}, {}, {})
        }
    }
}
