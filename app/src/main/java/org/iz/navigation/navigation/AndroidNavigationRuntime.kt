package org.iz.navigation.navigation

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import org.iz.navigation.data.*
import org.iz.navigation.tracking.*
import org.iz.navigation.weather.*
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import org.iz.navigation.speed.*

internal class AndroidNavigationRuntime(private val context: Context) : NavigationRuntime, NavigationCueRuntime, NavigationVoiceRuntime, RouteModificationPlanner {
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
    override suspend fun finishUnattached(id: String, stillUnattached: () -> Boolean) =
        tracker.finishIfUnattached(id, stillUnattached)
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
    override suspend fun prepareQuickSession(transport: Transport, stillCurrent: () -> Boolean,
        commit: (Journey?) -> Unit) = TrackingCoordinator.mutex.withLock {
        check(TrackingCoordinator.pendingManualStartId() == null) { "Mevcut kaydın başlamasını bekle." }
        super<NavigationRuntime>.prepareQuickSession(transport, stillCurrent, commit)
    }
    override suspend fun prepareTrackFollowSession(transport: Transport, stillCurrent: () -> Boolean,
        onDiscard: (Journey) -> Unit, commit: (Journey?) -> Unit) = TrackingCoordinator.mutex.withLock {
        check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
        val existing = repository.activeJourney()
        check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
        val reusable = trackRecordingToReuse(existing, transport, recordingId(), TrackingCoordinator.pendingManualStartId() != null)
        var discardedId: String? = null
        try {
            if (existing != null && reusable == null) {
                if (existing.status == JourneyStatus.TEMPORARY) repository.rejectJourney(existing.id)
                else repository.markInterrupted(existing.id)
                discardedId = existing.id
                onDiscard(existing)
                TrackingCoordinator.clearPendingManualStart(existing.id)
            }
            check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
            // Commit the session's GPS demand before releasing the same mutex used by manual/auto writers.
            commit(reusable)
        } finally {
            discardedId?.takeIf { TrackingService.runningJourneyId == it }?.let {
                TrackingService.detachRecording(context, it)
            }
        }
    }
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
        return plan(stops, transport, settings.preferences, settings.travelSpeedKmh)
    }
    override suspend fun plan(stops: List<RouteStop>, transport: Transport, preferences: RoutePreferences,
        travelSpeedKmh: Double?): PlannedRoute = ConfiguredRoutePlanner(context).plan(
            stops, System.currentTimeMillis(), transport, travelSpeedKmh, preferences)
    override suspend fun planModified(request: RouteModificationRequest): PlannedRoute =
        ConfiguredRoutePlanner(context).planModified(request)
    override fun trafficRefreshEnabled(route: PlannedRoute): Boolean {
        if (route.transport !in setOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE)) return false
        return TrafficSettingsStore(context).read().let { it.enabled && it.hasKey && it.freePlanAcknowledged }
    }

    override fun hasPreciseLocation() = TrackingController.hasFineLocation(context)

    override fun canLocateQuickly() = ContextCompat.checkSelfPermission(context,
        Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || hasPreciseLocation()

    @SuppressLint("MissingPermission")
    override suspend fun cachedQuickLocation(precise: Boolean): NavigationFix? {
        if (!canLocateQuickly()) return null
        val granularity = if (precise) Granularity.GRANULARITY_FINE else Granularity.GRANULARITY_COARSE
        val request = LastLocationRequest.Builder().setGranularity(granularity).setMaxUpdateAgeMillis(60_000).build()
        return LocationServices.getFusedLocationProviderClient(context)
            .getLastLocation(request).await()?.toNavigationFix()
    }

    @SuppressLint("MissingPermission")
    override suspend fun locateApproximate(): NavigationFix? {
        if (!canLocateQuickly()) return null
        val token = CancellationTokenSource()
        try {
            val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setGranularity(Granularity.GRANULARITY_COARSE).setMaxUpdateAgeMillis(60_000)
                .setDurationMillis(5_000).build()
            return LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(request, token.token).await()?.toNavigationFix()
        } finally { token.cancel() }
    }

    private fun Location.toNavigationFix() = NavigationFix(WeatherCoordinate(latitude, longitude), time,
        if (hasAccuracy()) accuracy else Float.POSITIVE_INFINITY,
        if (hasSpeed()) speed else null, if (hasBearing()) bearing else null)

    @SuppressLint("MissingPermission")
    override suspend fun locate(): NavigationFix {
        check(TrackingController.hasFineLocation(context)) { "Hassas konum iznini telefondan ver." }
        val token = CancellationTokenSource()
        return try {
            val location = withTimeout(15_000) { LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(5000).setDurationMillis(10_000).build(), token.token).await() }
                ?: error("Güncel konum alınamadı. Konum hizmetlerini kontrol et.")
            location.toNavigationFix()
        } finally { token.cancel() }
    }
    override fun render(state: NavigationState) {
        runCatching { notifications.update(state) }.onFailure {
            LocationDiagnostics(java.io.File(context.noBackupFilesDir, "diagnostics"))
                .record(LocationDiagnostics.Event.DISPLAY_FAILED, null, null)
        }
    }
    override fun prepareSpeech() { NavigationSpeech.get(context).prepareGuidance() }
    override fun prepareSpeech(mode: GuidanceVoiceMode) { NavigationSpeech.get(context).prepareGuidance(mode) }
    override val speechStatus get() = NavigationSpeech.get(context).status
    override fun speakCue(request: NavigationCueRequest) { NavigationSpeech.get(context).speakTurn(request) }
    override fun speak(text: String) { NavigationSpeech.get(context).speakTurn(text) }
    override fun cancelSpeech() { NavigationSpeech.get(context).cancelTurns() }
    override fun endGuidanceSpeech() { NavigationSpeech.get(context).endGuidanceVoice() }
}
