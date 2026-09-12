package org.iz.navigation.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import org.iz.navigation.data.*
import org.iz.navigation.tracking.*
import org.iz.navigation.weather.*
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import org.iz.navigation.speed.*

internal class AndroidNavigationRuntime(private val context: Context) : NavigationRuntime {
    private val repository = DiaryRepository(context)
    private val tracker = TrackingController(context)
    private val notifications = NavigationNotifications(context)
    private var demand: String? = null
    private val trafficSettings = TrafficSettingsStore(context)
    private val roadSpeeds = RoadSpeedMonitor(OsmRoadRepository(context)::nearby,
        trafficSettings::credentials, TomTomReverseSpeedClient(trafficSettings::credentials))
    private var speedSettingsGeneration = TrafficSettingsStore.changeGeneration
    override val roadSpeedMonitoring = true
    override fun roadSpeedState(state: NavigationState, now: Long): RoadSpeedState {
        if (speedSettingsGeneration != TrafficSettingsStore.changeGeneration) {
            speedSettingsGeneration = TrafficSettingsStore.changeGeneration
            roadSpeeds.invalidateCredentials()
        }
        return roadSpeeds.observe(state, now)
    }
    override suspend fun refreshRoadSpeed() { roadSpeeds.refresh() }
    override val journeys = repository.journeys
    override suspend fun activeJourney() = repository.activeJourney()
    override suspend fun start(transport: Transport, stillCurrent: () -> Boolean) = tracker.startManual(transport, stillCurrent)
    override suspend fun confirm(id: String, transport: Transport) { tracker.confirm(id, transport) }
    override suspend fun finish(id: String) { tracker.finish(id) }
    override suspend fun stopRecording(id: String) { tracker.finish(id, preserveSession = true) }
    override suspend fun discardCandidate(id: String) {
        tracker.reject(id)
        withTimeout(8000) { while (TrackingService.runningJourneyId == id) kotlinx.coroutines.delay(50) }
    }
    override suspend fun prepareNoRecordSession(stillCurrent: () -> Boolean,
        onDiscard: (Journey) -> Unit, commit: () -> Unit) = TrackingCoordinator.mutex.withLock {
        check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
        val existing = repository.activeJourney()
        check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
        require(existing == null || existing.status == JourneyStatus.TEMPORARY ||
            recordingId() != existing.id && !TrackingCoordinator.isManualStartPending(existing.id)) {
            "Kaydet kapalı başlatmak için önce mevcut kaydı durdur."
        }
        var discardedId: String? = null
        try {
            if (existing != null) {
                onDiscard(existing)
                if (existing.status == JourneyStatus.TEMPORARY) repository.rejectJourney(existing.id)
                else repository.markInterrupted(existing.id)
                discardedId = existing.id
                TrackingCoordinator.clearPendingManualStart(existing.id)
            }
            check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
            // No automatic/manual writer can cross the DB decision and this synchronous
            // session-demand commit; queued detections see the committed suppression.
            commit()
        } finally {
            discardedId?.takeIf { TrackingService.runningJourneyId == it }?.let {
                TrackingService.detachRecording(context, it)
            }
        }
    }
    override fun setPreparingNoRecord(enabled: Boolean) { TrackingService.preparingNoRecord = enabled }
    override fun locationActive() = TrackingService.locationDeliveryActive
    override fun setLocationSession(id: String?, transport: Transport?, highFrequency: Boolean, suppressAutomatic: Boolean) {
        TrackingService.setSession(context, id, transport, highFrequency, suppressAutomatic)
    }
    override suspend fun interrupt(id: String) {
        repository.markInterrupted(id)
        if (TrackingService.runningJourneyId == id) context.stopService(Intent(context, TrackingService::class.java))
    }
    override fun startPending(id: String) = TrackingCoordinator.isManualStartPending(id)
    override fun recordingId() = TrackingService.runningJourneyId.takeIf { TrackingService.isRunning }
    override fun setHighFrequency(journeyId: String?) {
        if (demand == journeyId) return
        demand = journeyId
        TrackingService.setNavigationJourney(context, journeyId)
    }
    override suspend fun plan(stops: List<RouteStop>, transport: Transport): PlannedRoute {
        val settings = WeatherSettingsStore(context).read(transport)
        return ConfiguredRoutePlanner(context).plan(stops, System.currentTimeMillis(), transport, settings.travelSpeedKmh)
    }
    override fun trafficRefreshEnabled(route: PlannedRoute): Boolean {
        if (route.transport !in setOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE)) return false
        return TrafficSettingsStore(context).read().let { it.enabled && it.hasKey && it.freePlanAcknowledged }
    }
    @SuppressLint("MissingPermission")
    override suspend fun locate(): NavigationFix {
        check(TrackingController.hasFineLocation(context)) { "Hassas konum iznini telefondan ver." }
        val token = CancellationTokenSource()
        return try {
            val location = withTimeout(15_000) { LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(5000).setDurationMillis(10_000).build(), token.token).await() }
                ?: error("Güncel konum alınamadı. Konum hizmetlerini kontrol et.")
            NavigationFix(WeatherCoordinate(location.latitude, location.longitude), location.time,
                if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
                if (location.hasSpeed()) location.speed else null, if (location.hasBearing()) location.bearing else null)
        } finally { token.cancel() }
    }
    override fun render(state: NavigationState) {
        runCatching { notifications.update(state) }.onFailure {
            LocationDiagnostics(java.io.File(context.noBackupFilesDir, "diagnostics"))
                .record(LocationDiagnostics.Event.DISPLAY_FAILED, null, null)
        }
    }
    override fun speak(text: String) { NavigationSpeech.get(context).speakTurn(text) }
    override fun cancelSpeech() { NavigationSpeech.get(context).cancelTurns() }
}
