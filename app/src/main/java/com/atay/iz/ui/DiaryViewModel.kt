package com.atay.iz.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atay.iz.data.*
import com.atay.iz.tracking.TrackingController
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
) {
    fun active(now: Long) = journeys.firstOrNull { it.endedAt == null && (it.expiresAt == null || it.expiresAt > now) }
    fun currentRoute(now: Long): List<TrackPoint> {
        val journeyId = active(now)?.id ?: return emptyList()
        return points.filter { it.journeyId == journeyId }
    }
}

class DiaryViewModel(application: Application) : AndroidViewModel(application) {
    val repository = DiaryRepository(application)
    val tracker = TrackingController(application)
    val osmReady = (application as com.atay.iz.IzApplication).osmReady
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    val state = combine(repository.journeys, repository.points, repository.places, repository.visits, repository.photos) { journeys, points, places, visits, photos ->
        DiaryState(journeys, points, places, visits, photos)
    }.combine(repository.drafts) { state, drafts -> state.copy(drafts = drafts) }
        .combine(repository.contributions) { state, contributions -> state.copy(contributions = contributions) }
        .combine(repository.healthSamples) { state, samples -> state.copy(healthSamples = samples) }
        .combine(repository.healthSummaries) { state, summaries -> state.copy(healthSummaries = summaries) }
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
