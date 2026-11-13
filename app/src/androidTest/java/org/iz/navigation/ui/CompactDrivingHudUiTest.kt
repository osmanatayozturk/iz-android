package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure HUD rendering: no native map, network, location, recorder or provider. */
@RunWith(AndroidJUnit4::class)
class CompactDrivingHudUiTest {
    @get:Rule val compose = createComposeRule()
    private val initial = CompactHudData("Sağa dön", "Manevra 200 m", "Kalan 3.0 km · 8 dk · 2 dk mola dahil",
        "30/50 km/sa", "Hızım30, sınır50", "18°", emptyList(), false, true)
    @Test fun portraitPanelHeightDoesNotFollowInstructionsOrWarnings() = verify(false, 1f)
    @Test fun landscapeFontTwoKeepsCriticalGpsAndRecordWarningsVisible() = verify(true, 2f)
    @Test fun preparedDisclosureUsesReservedSlotWithoutChangingPanelHeight() {
        var visible by mutableStateOf(false)
        val warnings = initial.copy(gpsWarning = true, recordingWarning = true,
            warnings = listOf("GPS konumu bekleniyor", "Kayıt: hassas ve güvenilir konum bekleniyor", "Duraklatıldı"))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { IzTheme {
            Box(Modifier.requiredSize(640.dp, 360.dp)) {
                CompactDrivingHud(warnings, true, {}, {}, {}, disclosure = {
                    if (visible) Text("Hazır rota: harita paketi yok", Modifier.testTag("prepared-map-status"),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall, maxLines = 1)
                }) {
                    IconButton({}, Modifier.size(48.dp).testTag("directions-recenter")) { Text("◎") }
                }
            }
        } } }
        val before = compose.onNodeWithTag("compact-guidance-header").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { visible = true }
        assertEquals(before, compose.onNodeWithTag("compact-guidance-header").fetchSemanticsNode().boundsInRoot)
        val status = compose.onNodeWithTag("prepared-map-status", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val slot = compose.onNodeWithTag("hud-prepared-status-slot", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(status.top >= slot.top && status.bottom <= slot.bottom)
        assertTrue(slot.top >= before.top && slot.bottom <= before.bottom)
        val statusLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("prepared-map-status", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(statusLayouts) }
        assertFalse(textLayoutDetails("prepared disclosure", statusLayouts.single()), statusLayouts.single().hasVisualOverflow)
        val warningSlot = compose.onNodeWithTag("hud-reserved-status").fetchSemanticsNode().boundsInRoot
        for (copy in listOf("GPS bekleniyor", "Kayıt bekleniyor", "Duraklatıldı")) {
            val node = compose.onNodeWithText(copy, useUnmergedTree = true).assertIsDisplayed()
            val measured = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(measured) }
            assertEquals(textLayoutDetails("warning: $copy", measured.single()), 1, measured.single().lineCount)
            assertFalse(textLayoutDetails("warning: $copy", measured.single()), measured.single().hasVisualOverflow)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= warningSlot.left && bounds.right <= warningSlot.right)
            assertTrue(bounds.top >= warningSlot.top && bounds.bottom <= warningSlot.bottom)
        }
        assertSummary("200 m · 3,0 km / 8 dk · 2 dk mola dahil",
            "Manevra 200 m · Kalan 3.0 km · 8 dk · 2 dk mola dahil", 1)
    }
    private fun verify(landscape: Boolean, fontScale: Float) {
        val summaryLayouts = mutableListOf<Pair<Boolean, String>>()
        var data by mutableStateOf(initial.copy(next = "Manevra —", remaining = "Kalan — · — · 2 dk mola dahil"))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) { IzTheme {
            Box(Modifier.requiredSize(if (landscape) 640.dp else 360.dp, if (landscape) 360.dp else 640.dp)) {
                CompactDrivingHud(data, landscape, {}, {}, {}) {
                    IconButton({}, Modifier.size(48.dp).testTag("directions-recenter")) { Text("◎") }
                }
            }
        } } }
        assertSummary("— · — / — · 2 dk mola dahil", "Manevra — · Kalan — · — · 2 dk mola dahil",
            if (landscape) 1 else 2, summaryLayouts)
        val originalHeight = compose.onNodeWithTag("compact-guidance-header").fetchSemanticsNode().boundsInRoot.height
        val full = "Sentetik kavşaktan sağa dön ve uzun sahil caddesini izleyerek ikinci yol ayrımından şehir merkezi yönünde devam et"
        val warning = "GPS konumu bekleniyor · Kayıt: hassas ve güvenilir konum bekleniyor"
        compose.runOnIdle { data = data.copy(instruction = full,
            warnings = listOf("GPS konumu bekleniyor", "Kayıt: hassas ve güvenilir konum bekleniyor"), weather = "Bilinmiyor",
            gpsWarning = true, recordingWarning = true) }
        assertEquals(originalHeight, compose.onNodeWithTag("compact-guidance-header").fetchSemanticsNode().boundsInRoot.height, .1f)
        compose.onNodeWithContentDescription(full).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("hud-instruction", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount <= 2)
        compose.onNodeWithContentDescription(warning, useUnmergedTree = true).assertIsDisplayed()
        val header = compose.onNodeWithTag("compact-guidance-header").fetchSemanticsNode().boundsInRoot
        for (copy in listOf("GPS bekleniyor", "Kayıt bekleniyor")) {
            val node = compose.onNodeWithText(copy, useUnmergedTree = true).assertIsDisplayed()
            val measured = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(measured) }
            assertEquals(textLayoutDetails("warning: $copy", measured.single()), 1, measured.single().lineCount)
            assertFalse(textLayoutDetails("warning: $copy", measured.single()), measured.single().hasVisualOverflow)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.top >= header.top && bounds.bottom <= header.bottom)
        }
        for (tag in listOf("compact-card-mute", "home-finish-session", "hud-weather", "directions-recenter")) {
            compose.onNodeWithTag(tag).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertHasClickAction()
        }
        val summaries = listOf(
            arrayOf("Manevra 200 m", "Kalan 3.0 km · 8 dk · 2 dk mola dahil",
                "200 m · 3,0 km / 8 dk · 2 dk mola dahil", "Manevra 200 m · Kalan 3.0 km · 8 dk · 2 dk mola dahil"),
            arrayOf("Manevra —", "Kalan — · — · 2 dk mola dahil",
                "— · — / — · 2 dk mola dahil", "Manevra — · Kalan — · — · 2 dk mola dahil"),
            arrayOf("Manevra 12.3 km", "Kalan 999.9 km · 206 dk · 3 dk mola dahil",
                "12,3 km · 999,9 km / 206 dk · 3 dk mola dahil", "Manevra 12.3 km · Kalan 999.9 km · 206 dk · 3 dk mola dahil"))
        for ((next, remaining, expected, description) in summaries) {
            compose.runOnIdle { data = data.copy(next = next, remaining = remaining) }
            assertSummary(expected, description, if (landscape) 1 else 2, summaryLayouts)
            assertEquals(originalHeight, compose.onNodeWithTag("compact-guidance-header").fetchSemanticsNode().boundsInRoot.height, 1f)
        }
        assertTrue("HUD_SUMMARY_LAYOUTS\n" + summaryLayouts.joinToString("\n") { "fits=${it.first}; ${it.second}" },
            summaryLayouts.all { it.first })
    }

    private fun assertSummary(expected: String, description: String, maxLines: Int,
        collectedLayouts: MutableList<Pair<Boolean, String>>? = null) {
        val node = compose.onNodeWithTag("hud-remaining", useUnmergedTree = true)
            .assertTextEquals(expected).assertContentDescriptionEquals(description).assertIsDisplayed()
        val measured = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(measured) }
        val layout = measured.single()
        assertEquals(12f, layout.layoutInput.style.fontSize.value, .01f)
        assertEquals(16f, layout.layoutInput.style.lineHeight.value, .01f)
        assertEquals(maxLines, layout.layoutInput.maxLines)
        val details = textLayoutDetails("summary: $expected", layout)
        if (collectedLayouts == null) {
            assertTrue(details, layout.lineCount <= maxLines)
            assertFalse(details, layout.hasVisualOverflow)
        } else collectedLayouts += (layout.lineCount <= maxLines && !layout.hasVisualOverflow) to details
    }

    private fun textLayoutDetails(copy: String, layout: TextLayoutResult) =
        "$copy; size=${layout.size}; constraints=${layout.layoutInput.constraints}; " +
            "multiParagraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
            "maxIntrinsicWidth=${layout.multiParagraph.maxIntrinsicWidth}; lineCount=${layout.lineCount}; " +
            "didOverflowWidth=${layout.didOverflowWidth}; didOverflowHeight=${layout.didOverflowHeight}; " +
            "didExceedMaxLines=${layout.multiParagraph.didExceedMaxLines}; " +
            "isLineEllipsized(0)=${layout.lineCount > 0 && layout.isLineEllipsized(0)}"
}
