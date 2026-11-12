package org.iz.navigation.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.placeinfo.PlaceInfoAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceInformationActionsUiTest {
    private class RecordingContext : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        val intents = mutableListOf<Intent>()
        var unavailable = false
        override fun startActivity(intent: Intent) {
            if (unavailable) throw ActivityNotFoundException()
            intents += intent
        }
    }
    @Test fun explicitPhoneUsesDialAndWebsiteUsesBrowsableView() {
        val context = RecordingContext()
        assertNull(openPlaceInformationAction(context, PlaceInfoAction("Phone", "tel:+903125551234", emptySet()), true))
        assertNull(openPlaceInformationAction(context, PlaceInfoAction("Site", "https://example.org", emptySet()), false))
        assertEquals(listOf(Intent.ACTION_DIAL, Intent.ACTION_VIEW), context.intents.map { it.action })
        assertTrue(context.intents.last().categories.contains(Intent.CATEGORY_BROWSABLE))
        assertFalse(context.intents.any { it.action == Intent.ACTION_CALL })
    }
    @Test fun unsafeActionsAndUnavailableAppsHaveNoUnintendedLaunch() {
        val context = RecordingContext()
        listOf("javascript:alert(1)", "intent://example", "https://user:secret@example.org", "file:///private")
            .forEach { assertNotNull(openPlaceInformationAction(context, PlaceInfoAction("Unsafe", it, emptySet()), false)) }
        assertNotNull(openPlaceInformationAction(context, PlaceInfoAction("USSD", "tel:*123%23", emptySet()), true))
        assertTrue(context.intents.isEmpty())
        context.unavailable = true
        assertEquals("Açılacak uygulama bulunamadı.", openPlaceInformationAction(context, PlaceInfoAction("Site", "https://example.org", emptySet()), false))
        assertTrue(context.intents.isEmpty())
    }
}
