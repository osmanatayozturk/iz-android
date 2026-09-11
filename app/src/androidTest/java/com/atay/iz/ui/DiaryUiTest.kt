package com.atay.iz.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.runBlocking
import com.atay.iz.data.DiaryRepository
import com.atay.iz.data.JourneyStatus
import com.atay.iz.data.Transport

@RunWith(AndroidJUnit4::class)
class DiaryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun userCanSavePlaceAndReturnToItWithoutLocationPermission() {
        val name = "Test durağı ${UUID.randomUUID().toString().take(6)}"
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithText("Bir yer kaydet").performScrollTo().performClick()
        compose.onNodeWithText("Bu yere verdiğin ad").performTextInput(name)
        compose.onNodeWithText("Bu ziyarete özel not").performTextInput("Özel yürüyüş notum")
        compose.onNodeWithText("Ziyareti kaydet").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Özel yürüyüş notum").assertIsDisplayed()
        compose.onNodeWithText("Yeni ziyaret").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2 ziyaret · Kişisel yer günlüğü").fetchSemanticsNodes().isNotEmpty() }
        val repository = DiaryRepository(compose.activity)
        val snapshot = runBlocking { repository.snapshot() }
        val place = snapshot.places.single { it.name == name }
        assertNull(place.latitude)
        assertEquals(2, snapshot.visits.count { it.placeId == place.id })
        assertTrue(snapshot.drafts.none { d -> snapshot.visits.any { it.id == d.visitId && it.placeId == place.id } })
        runBlocking { repository.deletePlace(place.id) }
    }

    @Test fun settingsAndTransportChoiceAreAvailable() {
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithText("Ayarlar").performScrollTo().performClick()
        compose.onNodeWithText("Hareketi fark et").assertIsDisplayed()
        compose.onNodeWithTag("return-home-map").performClick()
        compose.onNodeWithTag("home-record").performClick()
        listOf("Araba", "Motosiklet", "Bisiklet", "Yürüyüş", "Koşu", "Yolcu").forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("Vazgeç").performClick()
    }

    @Test fun editingTemporaryJourneyRequestsConfirmationBeforeMetadataChanges() {
        val repository = DiaryRepository(compose.activity)
        val title = "Bekleyen test ${UUID.randomUUID().toString().take(6)}"
        val journey = runBlocking {
            val pending = repository.createJourney(Transport.WALK, temporary = true)
            repository.finishJourney(pending.id)
            repository.saveJourney(repository.getJourney(pending.id)!!.copy(title = title))
            pending
        }
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithText("Yolculuklar").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithContentDescription("Yolculuğu düzenle").performClick()
        compose.onNodeWithText("Yolculuğunu sakla").assertIsDisplayed()
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(JourneyStatus.TEMPORARY, runBlocking { repository.getJourney(journey.id) }?.status)
        runBlocking { repository.rejectJourney(journey.id) }
    }

    @Test fun finishedTemporaryJourneyCanBePreservedWithoutStartingLocationService() {
        val repository = DiaryRepository(compose.activity)
        val title = "Saklanacak test ${UUID.randomUUID().toString().take(6)}"
        val journey = runBlocking {
            val pending = repository.createJourney(Transport.WALK, temporary = true)
            repository.finishJourney(pending.id)
            repository.saveJourney(repository.getJourney(pending.id)!!.copy(title = title))
            pending
        }
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithText("Yolculuklar").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Bu yolculuğu sakla"))
        compose.onNodeWithText("Bu yolculuğu sakla").performClick()
        compose.onNodeWithText("Kaydı başlat / sakla").performClick()
        compose.waitUntil(10_000) { runBlocking { repository.getJourney(journey.id)?.status == JourneyStatus.CONFIRMED } }
        assertNotNull(runBlocking { repository.getJourney(journey.id)?.endedAt })
        assertNull(runBlocking { repository.activeJourney() })
        runBlocking { repository.deleteJourney(journey.id) }
    }
}

