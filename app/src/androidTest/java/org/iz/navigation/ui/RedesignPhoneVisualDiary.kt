package org.iz.navigation.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyHealthSummary
import org.iz.navigation.data.JourneyStats
import org.iz.navigation.data.Photo
import org.iz.navigation.power.AnalysisEntry
import org.iz.navigation.power.AnalysisPhase
import org.iz.navigation.power.AnalysisState
import java.io.File

internal fun PhoneSceneHost.diaryScene(id: String) {
    val journey = PhoneVisualFixture.journey
    val stats = JourneyStats(4_200.0, 420_000, 370_000, 310_000, 60_000, 50_000, 36.0, 48.8, 61.0)
    when (id) {
        "photos" -> photoScene()
        "statsdetail" -> {
            show { PhoneVisualScroll { JourneyStatsPanel(journey, stats, "Hazırlanıyor") } }
            capture(id, hasText("Hareket ve durma"), "JourneyStatsPanel", "GPS metrics component without health samples")
        }
        "reports" -> {
            val state = DiaryState(journeys = listOf(journey),
                analysis = AnalysisState(AnalysisPhase.WAITING_BATTERY))
            show { PhoneVisualScroll { RecordedCurvePanel(journey, state) } }
            capture(id, hasText("GPS virajları"), "RecordedCurvePanel", "Report waiting for battery without cache writes")
        }
        "compare" -> comparisonScene(journey, stats)
        else -> error("Unallocated diary scene $id")
    }
}

private fun PhoneSceneHost.comparisonScene(first: Journey, stats: JourneyStats) {
    val second = first.copy(id = "visual-journey-2", title = "Sentetik sabah gezisi",
        startedAt = first.startedAt - 86_400_000, endedAt = checkNotNull(first.endedAt) - 86_400_000)
    val state = DiaryState(journeys = listOf(first, second), analysis = AnalysisState(AnalysisPhase.READY, pending = false,
        entries = mapOf(first.id to visualAnalysis(first, stats, 'a'),
            second.id to visualAnalysis(second, stats.copy(distanceMeters = 3_700.0, maxSpeedKmh = 52.0), 'b'))))
    show { PhoneVisualScroll { JourneyComparisonPanel(state) } }
    compose.onNode(hasText("Birinci yolculuk") and hasClickAction()).performClick()
    compose.onNode(hasText(first.title) and hasClickAction()).performClick()
    compose.onNode(hasText("İkinci yolculuk") and hasClickAction()).performClick()
    compose.onNode(hasText(second.title) and hasClickAction()).performClick()
    capture("compare", hasText("Süre ve mesafe farkı"), "JourneyComparisonPanel", "Two synthetic prepared GPS summaries")
}

private fun PhoneSceneHost.photoScene() {
    val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    val file = File.createTempFile("visual-gallery-", ".png", files)
    val color = 0xff2e897f.toInt()
    try {
        val bitmap = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xfff1bd65.toInt() }
            canvas.drawCircle(170f, 75f, 43f, paint)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val photo = Photo(id = "visual-gallery", relativePath = file.name, caption = "Sentetik gezi görseli",
            takenAt = PhoneVisualFixture.epoch, journeyId = PhoneVisualFixture.journey.id)
        show { PhoneVisualScroll { DiaryPhotoGallery(listOf(photo),
            onDelete = { unexpectedVisualAction() }, onEdit = { unexpectedVisualAction() }) } }
        capture("photos", hasTestTag("diary-photo-edit-visual-gallery"), "DiaryPhotoGallery",
            "Generated bitmap, temporary private file", requiredColor = color)
    } finally {
        check(file.delete()) { "Temporary gallery image cleanup failed" }
    }
}

private fun visualAnalysis(journey: Journey, stats: JourneyStats, fingerprint: Char): AnalysisEntry {
    val absentHealth = JourneyHealthSummary(journey.id)
    return AnalysisEntry(fingerprint.toString().repeat(64), stats, absentHealth, absentHealth, journey)
}
