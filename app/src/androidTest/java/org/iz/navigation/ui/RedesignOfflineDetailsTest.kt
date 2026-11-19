package org.iz.navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.group.GroupBackend
import org.iz.navigation.group.GroupCoordinator
import org.iz.navigation.group.GroupNavigation
import org.iz.navigation.integration.osm.OsmAuthManager
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.osmcommunity.OsmCommunityProfile
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class RedesignOfflineDetailsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val stops = listOf(
        RouteStop("Sentetik başlangıç", WeatherCoordinate(0.0, 0.0)),
        RouteStop("Sentetik ara durak", WeatherCoordinate(0.0, 1.0)),
        RouteStop("Sentetik varış", WeatherCoordinate(0.0, 2.0)),
    )

    @Test fun selectedStopMapCanBeCancelledWithoutChangingThePlan() = withPlanner { planner ->
        compose.onNodeWithTag("weather_planner").performScrollToNode(hasText(stops.first().label))
        compose.onAllNodesWithText("Kayıtlı / Ara / Harita").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Başlangıç noktasını seç").assertIsDisplayed()
        compose.onNodeWithText("Haritadan seç").performClick()
        compose.onNodeWithTag("fullscreen_map_viewport").assertIsDisplayed()
        compose.onNodeWithText("Bu noktayı kullan").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Tam ekran haritayı kapat").performClick()
        compose.onNodeWithTag("fullscreen_map").assertDoesNotExist()
        compose.onNodeWithText("Başlangıç noktasını seç").assertIsDisplayed()
        compose.onNodeWithText("Haritadan seç").assertIsDisplayed()
        compose.onNodeWithText("Vazgeç").performClick()
        compose.onNodeWithText("Başlangıç noktasını seç").assertDoesNotExist()
        assertPlan(planner, stops)
    }

    @Test fun plannedBreakCancellationAndApplyAffectOnlyTheSelectedStop() = withPlanner { planner ->
        val addBreak = "${stops[1].label}: Mola ekle"
        compose.onNodeWithTag("weather_planner").performScrollToNode(hasContentDescription(addBreak))
        compose.onNodeWithContentDescription(addBreak).performClick()
        compose.onNodeWithText("Planlı mola").assertIsDisplayed()
        compose.onNodeWithText("Molayı uygula").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("25")
        compose.onNodeWithText("Vazgeç").performClick()
        compose.onNodeWithText("Planlı mola").assertDoesNotExist()
        assertPlan(planner, stops)

        compose.onNodeWithContentDescription(addBreak).performScrollTo().performClick()
        compose.onNodeWithText("Molayı uygula").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("15")
        compose.onNodeWithText("Molayı uygula").assertIsEnabled().performClick()
        compose.onNodeWithText("Planlı mola").assertDoesNotExist()
        assertPlan(planner, listOf(stops.first(), stops[1].copy(plannedBreakSeconds = 900), stops.last()))
    }

    @Test fun syntheticCommunityProfileShowsDetailsAndReturnsWithoutExternalActions() {
        requireOfflineQa()
        val profile = OsmCommunityProfile(id = 12001, displayName = "Sentetik OSM üyesi",
            description = "Çevrimdışı profil açıklaması", changesetCount = 7)
        val profileOpen = mutableStateOf(true)
        Ux0111ComposeHost(compose).use { host ->
            host.setContent {
                IzTheme {
                    if (profileOpen.value) CommunityProfileView(profile, own = false, canMessage = false,
                        onMessage = { error("Synthetic profile must not compose a message") },
                        onLink = { error("External profile links must not open") },
                        onClose = { profileOpen.value = false })
                    else Text("Profil listesine dönüldü")
                }
            }
            compose.onNodeWithText(profile.displayName).assertIsDisplayed()
            compose.onNodeWithText("OSM kullanıcı kimliği: 12001").assertIsDisplayed()
            compose.onNodeWithText("7 değişiklik kümesi").assertIsDisplayed()
            compose.onNodeWithText(profile.description).assertIsDisplayed()
            compose.onNodeWithTag("community-profile-compose").assertIsNotEnabled()
            compose.onNodeWithText("Geri").performClick()
            compose.onNodeWithText(profile.displayName).assertDoesNotExist()
            compose.onNodeWithText("Profil listesine dönüldü").assertIsDisplayed()
        }
    }

    @Test fun approvedSyntheticGroupKeepsSharingOffWhenLeaveIsCancelled() {
        requireOfflineQa()
        val backend = ApprovedGroupBackend()
        val navigation = object : GroupNavigation {
            override val state = MutableStateFlow(NavigationState())
            override suspend fun setSharingLocation(enabled: Boolean, expectedSessionId: String?) {
                error("Synthetic group must not change location sharing")
            }
            override suspend fun currentLocation(): NavigationFix = error("Synthetic group must not locate")
        }
        val group = GroupCoordinator(backend, navigation)
        Ux0111ComposeHost(compose).use { host ->
            try {
                host.setContent {
                    IzTheme { GroupScreen(group, onClose = {}, onOpenRoute = { _, _ -> error("Unexpected route") }) }
                }
                compose.waitUntil(5_000) { group.state.value.selfStatus == "approved" }
                compose.onNodeWithText("Konum paylaşımı · Kapalı").performScrollTo().performClick()
                compose.onNode(isToggleable()).performScrollTo().assertIsOff().assertIsNotEnabled()
                compose.onNodeWithText("Gruptan ayrıl").performScrollTo().performClick()
                compose.onNodeWithText("Gruptan ayrıl?").assertIsDisplayed()
                compose.onNodeWithText("Vazgeç").performClick()
                compose.onNodeWithText("Gruptan ayrıl?").assertDoesNotExist()
                compose.onNodeWithText("Konum paylaşımı · Kapalı").performScrollTo().assertIsDisplayed()
                compose.runOnIdle {
                    assertEquals("qa-group", group.state.value.groupId)
                    assertFalse(group.state.value.sharing)
                    assertNull(group.state.value.pendingExit)
                    assertEquals(setOf("status"), backend.actions.toSet())
                }
            } finally {
                compose.runOnIdle { group.dispose() }
            }
        }
    }

    @Test fun disconnectedEmptyMapEditListReturnsWithoutChangingDrafts() {
        requireOfflineQa()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = OsmAuthManager.get(context)
        val repository = DiaryRepository(context)
        val baseline = runBlocking {
            withTimeout(5_000) {
                assertNull(auth.state.first { !it.busy }.user)
                assertNull(auth.currentCredentials())
                repository.mapEdits.first()
            }
        }
        assertTrue("An empty QA draft store is required", baseline.isEmpty())
        val pageOpen = mutableStateOf(true)
        Ux0111ComposeHost(compose).use { host ->
            host.setContent {
                IzTheme {
                    if (pageOpen.value) MapEditPage(onClose = { pageOpen.value = false })
                    else Text("Taslak listesinden dönüldü")
                }
            }
            compose.onNodeWithText("Mekân taslakları").assertIsDisplayed()
            compose.onNodeWithText("Henüz bir mekân taslağı yok.").assertIsDisplayed()
            compose.onNodeWithText("Yeni mekân").assertIsEnabled()
            compose.onNodeWithTag("osm-map-drafts-back").performClick()
            compose.onNodeWithText("Mekân taslakları").assertDoesNotExist()
            compose.onNodeWithText("Taslak listesinden dönüldü").assertIsDisplayed()
        }
        assertEquals(baseline, runBlocking { withTimeout(5_000) { repository.mapEdits.first() } })
    }

    @Test fun unknownMapSubmissionCanCloseWithoutCheckingOrResending() {
        requireOfflineQa()
        val draft = MapEditDraft(id = "qa-unknown-map-edit", latitude = 0.0, longitude = 0.0,
            name = "Sentetik belirsiz taslak", status = MapEditStatus.UNKNOWN)
        val statusOpen = mutableStateOf(true)
        var checks = 0
        Ux0111ComposeHost(compose).use { host ->
            host.setContent {
                IzTheme {
                    if (statusOpen.value) MapEditSubmissionStatus(
                        draft, target = null, busy = false, message = null,
                        onClose = { statusOpen.value = false }, onCheck = { checks++ },
                    ) else Text("Gönderim durumu kapatıldı")
                }
            }
            compose.onNodeWithText("Çatışma ve belirsiz gönderim").assertIsDisplayed()
            compose.onNodeWithText(draft.name).assertIsDisplayed()
            compose.onNodeWithText("Sonuç kontrol edilmeli").assertExists()
            compose.onNodeWithText("Sonucu kontrol et. Belirsiz işlem yeniden gönderilmez.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("osm-map-submission-check").performScrollTo().assertIsEnabled()
            compose.onAllNodes(hasClickAction() and hasText("Yeniden gönder", substring = true)).assertCountEquals(0)
            compose.runOnIdle { assertEquals(0, checks) }
            compose.onNodeWithContentDescription("Geri").performScrollTo().performClick()
            compose.onNodeWithText("Çatışma ve belirsiz gönderim").assertDoesNotExist()
            compose.onNodeWithText("Gönderim durumu kapatıldı").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, checks) }
        }
    }

    private fun withPlanner(block: (WeatherPlannerViewModel) -> Unit) {
        requireOfflineQa()
        lateinit var planner: WeatherPlannerViewModel
        Ux0111ComposeHost(compose).use { host ->
            host.setContent {
                val model = viewModel<WeatherPlannerViewModel>()
                SideEffect { planner = model }
                IzTheme { WeatherPlannerScreen(onClose = {}, vm = model) }
            }
            // Opening a saved plan can trigger a calculation. Seed only the in-memory stops.
            compose.runOnIdle { planner.setStops(stops) }
            block(planner)
        }
    }

    private fun assertPlan(planner: WeatherPlannerViewModel, expected: List<RouteStop>) {
        compose.runOnIdle {
            assertEquals(expected, planner.state.value.stops)
            assertNull(planner.state.value.route)
            assertTrue(planner.state.value.forecasts.isEmpty())
            assertNull(planner.navigation.state.value.sessionId)
        }
    }

    private fun requireOfflineQa() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("org.iz.navigation.qaredesign20261119", app.packageName)
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.INTERNET))
    }

    private class ApprovedGroupBackend : GroupBackend {
        override val configured = true
        override val hasIdentity = true
        val actions = CopyOnWriteArrayList<String>()

        override suspend fun command(action: String, body: JSONObject): JSONObject {
            actions += action
            check(action == "status" && !body.optBoolean("keepalive"))
            return JSONObject("""
                {"user_id":"qa-member","group":{"id":"qa-group","host_id":"qa-host",
                "expires_at":${System.currentTimeMillis() + 3_600_000},"self_status":"approved",
                "consent":false,"inbox":"qa-inbox","stops":[],
                "members":[{"user_id":"qa-member","name":"Sentetik üye","status":"approved","consent":false}]}}
            """.trimIndent())
        }

        override suspend fun token(): String = error("Synthetic group must not authenticate")
        override fun openSocket(listener: WebSocketListener): WebSocket = error("Synthetic group must not open a connection")
    }
}
