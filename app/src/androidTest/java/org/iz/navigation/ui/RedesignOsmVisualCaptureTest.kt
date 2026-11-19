package org.iz.navigation.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.data.ContributionDraft
import org.iz.navigation.data.ContributionKind
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SurveyAction
import org.iz.navigation.data.SurveyFieldObservation
import org.iz.navigation.data.SurveyObservation
import org.iz.navigation.data.SurveyVenue
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.encode
import org.iz.navigation.data.newMapEditDraft
import org.iz.navigation.data.selectPreset
import org.iz.navigation.integration.OsmGpxPreparation
import org.iz.navigation.integration.OsmSurveySnapshot
import org.iz.navigation.integration.osm.OsmAuthManager
import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.iz.navigation.integration.osm.OsmGpxHistoryEntry
import org.iz.navigation.integration.osm.OsmGpxReceiptKey
import org.iz.navigation.integration.osm.OsmGpxVisibility
import org.iz.navigation.integration.osm.editorDraft
import org.iz.navigation.integration.prepareOsmGpxTrace
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.iz.navigation.osmcommunity.CommunityDraft
import org.iz.navigation.osmcommunity.OsmCommunityProfile
import org.iz.navigation.osmcommunity.OsmMessageDetail
import org.iz.navigation.osmcommunity.OsmMessageSummary
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.LocalDate
import java.util.UUID

/** One offline QA composition and one frame per design ID. */
@RunWith(AndroidJUnit4::class)
class RedesignOsmVisualCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val timestamp = 1_763_718_000_000L
    private val today = LocalDate.of(2026, 11, 20)
    private val mapper = OsmCommunityProfile(81L, "Sentetik haritacı",
        description = "Örnek harita katkıları ve erişilebilirlik gözlemleri.", changesetCount = 12)
    private val note = ContributionDraft(id = "11111111-1111-4111-8111-111111111111",
        latitude = 0.01, longitude = 0.02, observedAt = timestamp,
        kind = ContributionKind.WRONG_DETAILS, text = "Sentetik gözlem: girişin konumu haritada güncellenmeli.")
    private val catalog by lazy {
        context.assets.open(OsmPresetCatalog.BUNDLED_ASSET).use { OsmPresetCatalog.load(it) }
    }
    private val target = OsmCorrectionElement(OsmRef(OsmType.NODE, 700), 12, 99, 30,
        "0.01", "0.02", emptyList(), mapOf("amenity" to "cafe", "name" to "Sentetik kafe",
            "opening_hours" to "Mo-Fr 09:00-18:00", "check_date:opening_hours" to "2020-01-02"))
    private val gpsEntry = OsmGpxHistoryEntry(120L, "sentetik-iz.gpx", timestamp, false)

    @Test fun captureContrib() = hub("contrib", false)

    @Test fun captureActivity() = hub("activity", true)

    private fun hub(id: String, activity: Boolean) = frame(
        id, hasText(if (activity) "Etkinlikler ve taslaklar" else "Katkı Merkezi"),
        prepare = {
            if (activity) {
                val action = hasTestTag("osm-contribution-activities")
                compose.onNodeWithTag("osm-contribution-list").performScrollToNode(action)
                compose.onNode(action).performClick()
            }
        },
    ) {
        OsmContributionHubContent(
            rows = listOf(OsmContributionHubRow.Note(note), OsmContributionHubRow.Gps(gpsEntry)),
            history = null, canRefresh = false, onClose = {}, onNewNote = {}, onNewPlace = {},
            onPickExistingPlace = {}, onGps = {}, onGpsHistory = {}, onRefresh = { unexpected() },
            onOpen = {}, onManageNotes = {}, onManagePlaces = {}, onCommunity = {},
        )
    }

    @Test fun captureNotes() = frame("notes", hasText("Harita notları")) {
        ContributionsScreen(listOf(note), busy = false, onNew = {}, onEdit = {},
            onCheck = { unexpected() }, onDelete = { unexpected() })
    }

    @Test fun captureNote() = frame("note", hasText("Harita notu ve konum seçimi"), dialog = true) {
        ContributionEditor(note, connected = false, busy = false, onDismiss = {},
            onSave = { unexpected() }, onPublish = { unexpected() }, onLogin = { unexpected() })
    }

    @Test fun captureEdits() = frame(
        "edits", hasText("Henüz bir mekân taslağı yok."),
        preflight = {
            check(runBlocking { withTimeout(5_000) { DiaryRepository(context).mapEdits.first().isEmpty() } })
        },
    ) { MapEditPage(onClose = {}) }

    @Test fun captureNewosm() = newEditor("newosm", "Yeni mekân")

    @Test fun capturePresets() = newEditor("presets", "Tür kataloğu ve favoriler") {
        compose.onNodeWithTag("osm-map-type").performScrollTo().performClick()
    }

    @Test fun capturePublish() = newEditor("publish", "OSM gönderim önizlemesi") {
        compose.onNodeWithText("Önizle").performScrollTo().performClick()
        compose.onNodeWithText("OSM gönderim önizlemesi").performScrollTo()
    }

    private fun newEditor(id: String, title: String, prepare: () -> Unit = {}) =
        frame(id, hasText(title), dialog = true, prepare = prepare) {
            val initial = remember(id) {
                val draft = newMapEditDraft(0.01, 0.02, "Sentetik kafe")
                if (id == "publish") draft.selectPreset(catalog.snapshot("amenity/cafe", OsmPresetGeometry.POINT))
                    .copy(surveyConfirmed = true, observedAt = timestamp)
                else draft.copy(observedAt = timestamp)
            }
            MapEditEditor(initial, busy = false, canWriteMap = false, authBusy = false,
                authError = null, onClose = {}, onSave = { unexpected() }, onPreview = { it.editableCopy() },
                onLogin = { unexpected() }, onPublish = { unexpected() }, catalog = catalog,
                favoriteIds = setOf("amenity/cafe"), onNearby = {})
        }

    @Test fun captureCorrection() = frame("correction", hasText("Mevcut mekânı düzelt"), dialog = true) {
        val initial = target.editorDraft(catalog, "22222222-2222-4222-8222-222222222222", 0.01, 0.02)
        MapEditCorrectionEditor(initial, target, busy = false, canWriteMap = false, authBusy = false,
            authError = null, liveBaseline = { initial }, liveBusy = { false }, onClose = {},
            onSave = { unexpected() }, onReadTarget = { target }, onReview = { unexpected() },
            isReviewCurrent = { false }, onLogin = { unexpected() }, onPublish = { unexpected() },
            onNoteFallback = {}, catalog = catalog)
    }

    @Test fun captureConflict() = frame("conflict", hasText("Çatışma ve belirsiz gönderim"), dialog = true) {
        val draft = MapEditDraft(latitude = 0.01, longitude = 0.02, name = "Sentetik taslak",
            status = MapEditStatus.UNKNOWN, targetType = OsmType.NODE, targetId = 700L)
        MapEditSubmissionStatus(draft, target = target.copy(version = 13), busy = false,
            message = null, onClose = {}, onCheck = { unexpected() })
    }

    @Test fun captureSurvey() = frame(
        "survey", hasTestTag("osm-survey-venue-NODE-700"),
        prepare = { compose.onNodeWithTag("osm-survey-list-view").performClick() },
    ) {
        val venue = SurveyVenue(target.ref, target.version, "Sentetik kafe", 0.01, 0.02, target.tags)
        val snapshot = OsmSurveySnapshot(WeatherCoordinate(0.01, 0.02), listOf(venue),
            timestamp, "https://fixture.invalid/overpass", capped = false)
        OsmSurveyNearbyContent(snapshot, loading = false, error = null,
            onRefresh = { unexpected() }, onOpen = {}, today = today, mapContent = { _, _ -> })
    }

    @Test fun captureSurveyedit() = surveyEditor("surveyedit", "Yerinde gözlem ve doğrulama")

    @Test fun captureHoursform() = surveyEditor("hoursform", "Saat düzenleyici") {
        compose.onNodeWithTag("osm-survey-editor-list").performScrollToNode(hasTestTag("osm-survey-hours-open"))
        compose.onNodeWithTag("osm-survey-hours-open").performClick()
    }

    private fun surveyEditor(id: String, title: String, prepare: () -> Unit = {}) =
        frame(id, hasText(title), dialog = true, prepare = prepare) {
            val observation = SurveyObservation(baseVersion = target.version, observedOn = today.toString(),
                hours = SurveyFieldObservation(
                    action = if (id == "hoursform") SurveyAction.EDIT else SurveyAction.UNTOUCHED,
                    value = if (id == "hoursform") "Mo-Fr 09:00-18:00" else "",
                    before = target.tags["opening_hours"], beforeDate = "2020-01-02",
                ),
                wheelchair = SurveyFieldObservation(before = null, beforeDate = null))
            val initial = MapEditDraft(id = "33333333-3333-4333-8333-333333333333",
                latitude = 0.01, longitude = 0.02, targetType = target.ref.type,
                targetId = target.ref.id, surveyPayload = observation.encode())
            OsmSurveyEditor(initial, target, busy = false, canWriteMap = false, authBusy = false,
                authError = null, liveBaseline = { initial }, liveBusy = { false }, onClose = {},
                onSave = { unexpected() }, onReadTarget = { target }, onReview = { unexpected() },
                isReviewCurrent = { false }, onLogin = { unexpected() }, onPublish = { unexpected() },
                onNoteFallback = {}, today = today)
        }

    @Test fun captureGps() = frame("gps", hasText("Paylaşılabilecek izler")) {
        Column(Modifier.fillMaxSize()) {
            val first = OsmGpsCandidate(OsmGpxReceiptKey("qa-one", "a".repeat(64)), "Sentetik rota 1", timestamp, 2200.0)
            val second = OsmGpsCandidate(OsmGpxReceiptKey("qa-two", "b".repeat(64)), "Sentetik rota 2", timestamp, 3550.0)
            OsmGpsCandidates(listOf(first, second), setOf(first.key), enabled = true,
                onToggleAll = {}, onToggle = {}, onPreview = {})
        }
    }

    @Test fun captureGpspreview() = frame("gpspreview", hasText("GPS önizleme ve toplu onay"), dialog = true) {
        val journey = Journey(id = "qa-trace", title = "Sentetik kıyı izi", startedAt = timestamp,
            endedAt = timestamp + 300_000)
        val points = (0..10).map { index ->
            TrackPoint(journeyId = journey.id, latitude = 0.01, longitude = 0.02 + index * 0.005,
                recordedAt = timestamp + index * 30_000L, accuracy = 5f)
        }
        val trace = (prepareOsmGpxTrace(journey, points) as OsmGpxPreparation.Ready).trace
        OsmGpsConsentDialog(mapper.displayName, OsmGpxVisibility.TRACKABLE, OsmGpsConsentPreview(trace, 0, 1),
            enabled = true, onVisibility = {}, onPreview = {}, onConfirm = { unexpected() }, onDismiss = {})
    }

    @Test fun captureGpshistory() = frame("gpshistory", hasText("Hesaba ait GPS izleri")) {
        Column(Modifier.fillMaxSize()) {
            OsmGpsHistoryList(listOf(gpsEntry, OsmGpxHistoryEntry(121L, "sentetik-bekleyen.gpx", timestamp + 600_000, true)),
                onOpen = { unexpected() })
        }
    }

    @Test fun captureMail() = frame("mail", hasText("Topluluk ve posta kutuları")) {
        OsmCommunityScreen(onClose = {}, onOpenSettings = {})
    }

    @Test fun captureMessage() = frame("message", hasText("Mesaj ayrıntısı")) {
        val summary = OsmMessageSummary(1L, mapper.id, mapper.displayName, 82L, "Sentetik alıcı",
            "Örnek harita notu", timestamp, true)
        CommunityMessageView(OsmMessageDetail(summary, "Sentetik mesaj. Erişim bilgisi sahada yeniden gözlemlendi."),
            accountId = 82L, busy = false, onClose = {}, onReply = {}, onRead = { unexpected() },
            onDelete = { unexpected() }, onProfile = {}, onLink = { unexpected() })
    }

    @Test fun captureCompose() = frame("compose", hasText("Mesaj yazma ve yanıt")) {
        val draft = CommunityDraft(accountId = 82L, recipientId = mapper.id, recipientName = mapper.displayName,
            title = "Örnek erişim bilgisi", body = "Sentetik taslak metni")
        CommunityComposer(draft, canSend = false, busy = false, onChange = {}, onClose = {},
            onSend = { unexpected() }, onDelete = { unexpected() }, onOutbox = {}, onCopy = { unexpected() })
    }

    @Test fun captureContacts() = frame("contacts", hasTestTag("community-contacts-list")) {
        CommunityContacts(listOf(mapper, OsmCommunityProfile(82L, "Sentetik ikinci kişi")), busy = false,
            onProfile = {}, onName = { unexpected() }, onLink = { unexpected() })
    }

    @Test fun captureProfile() = frame("profile", hasText(mapper.displayName)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            CommunityProfileView(mapper, own = false, canMessage = false,
                onMessage = { unexpected() }, onLink = { unexpected() }, onClose = {})
        }
    }

    @Test fun captureOsmsettings() = frame("osmsettings", hasText("OSM ve harita servisleri")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            OsmSettingsPanel(onContributions = {}, onMessage = { unexpected() }, onAccount = {})
        }
    }

    private fun frame(
        id: String, expected: SemanticsMatcher, dialog: Boolean = false,
        preflight: () -> Unit = {}, prepare: () -> Unit = {}, content: @Composable () -> Unit,
    ) {
        val runArgument = InstrumentationRegistry.getArguments().getString("izRedesignOsmRun")
        assumeTrue("Explicit visual capture run required", runArgument != null)
        val run = UUID.fromString(requireNotNull(runArgument)).toString()
        check(context.packageName == "org.iz.navigation.qaredesign20261119")
        check(context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
        val auth = OsmAuthManager.get(context)
        check(runBlocking { withTimeout(5_000) { auth.state.first { !it.busy }.user == null } })
        check(auth.currentCredentials() == null)
        preflight()
        val output = frameFile(context, run, id)
        check(!output.exists()) { "Frame already exists for design ID $id" }
        Ux0111ComposeHost(compose).use { host ->
            host.setContent {
                IzTheme { Surface(Modifier.fillMaxSize().testTag("osm-visual-root")) { content() } }
            }
            prepare()
            compose.onNode(expected).assertIsDisplayed()
            val root = compose.onNode(
                if (dialog) isDialog() and (expected or hasAnyDescendant(expected))
                else hasTestTag("osm-visual-root"),
                useUnmergedTree = true,
            )
            val bitmap = root.captureToImage().asAndroidBitmap()
            try {
                check(bitmap.width in 1..4096 && bitmap.height in 1..4096)
                Files.newOutputStream(output.toPath(), CREATE_NEW, WRITE).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
                check(output.length() > 0)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun frameFile(context: Context, run: String, id: String): File {
        val cache = context.cacheDir.canonicalFile
        val root = File(cache, "redesign-osm-visual")
        val folder = File(root, run)
        check(!Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == cache)
        check(!Files.isSymbolicLink(folder.toPath()) && folder.canonicalFile.parentFile == root.canonicalFile)
        check(folder.mkdirs() || folder.isDirectory)
        return File(folder, "$id.png")
    }

    private fun unexpected(): Nothing = error("Visual capture must not perform a live or persistent action")
}
