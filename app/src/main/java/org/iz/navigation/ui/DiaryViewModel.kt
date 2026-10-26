package org.iz.navigation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.iz.navigation.data.*
import org.iz.navigation.tracking.TrackingController
import org.iz.navigation.power.AnalysisState
import org.iz.navigation.power.AnalysisEntry
import org.iz.navigation.power.PostJourneyAnalysis
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DiaryState(
    val journeys: List<Journey> = emptyList(),
    val points: List<TrackPoint> = emptyList(),
    val places: List<Place> = emptyList(),
    val visits: List<Visit> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val drafts: List<ShareDraft> = emptyList(),
    val contributions: List<ContributionDraft> = emptyList(),
    val healthSamples: List<JourneyHealthSample> = emptyList(),
    val healthSummaries: List<JourneyHealthSummary> = emptyList(),
    val analysis: AnalysisState = AnalysisState(),
) {
    val analysisLabel: String get() = if (analysis.phase == org.iz.navigation.power.AnalysisPhase.READY)
        "Ayrıntılar hazırlanıyor" else analysis.label
    fun prepared(journey: Journey): AnalysisEntry? = analysis.ready(journey.id)?.takeIf { it.matches(journey) }
    fun active(now: Long) = journeys.firstOrNull { it.endedAt == null && (it.expiresAt == null || it.expiresAt > now) }
    fun currentRoute(now: Long): List<TrackPoint> {
        val journeyId = active(now)?.id ?: return emptyList()
        return points.filter { it.journeyId == journeyId }
    }
}

class DiaryViewModel(application: Application) : AndroidViewModel(application) {
    val repository = DiaryRepository(application)
    val tracker = TrackingController(application)
    val osmReady = (application as org.iz.navigation.IzApplication).osmReady
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    // Metadata does not subscribe to track_points or health sample tables. A GPS fix must not
    // materialize every journey's full track or rebuild all health/statistical summaries.
    private val analysis = PostJourneyAnalysis.get(application)
    val state = combine(repository.journeys.distinctUntilChanged(), repository.places, repository.visits, repository.photos) { journeys, places, visits, photos ->
        DiaryState(journeys = journeys, places = places, visits = visits, photos = photos)
    }.combine(repository.drafts) { state, drafts -> state.copy(drafts = drafts) }
        .combine(repository.contributions) { state, contributions -> state.copy(contributions = contributions) }
        .combine(analysis.state) { state, prepared -> state.copy(analysis = prepared) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryState())

    fun execute(success: String? = null, action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action(); if (success != null) messages.send(success) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { messages.send(e.message ?: "İşlem tamamlanamadı. Yeniden deneyebilirsin.") }
        }
    }
    fun message(text: String) { viewModelScope.launch { messages.send(text) } }
}
