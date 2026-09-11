package com.atay.iz.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.atay.iz.data.DiaryRepository
import com.atay.iz.data.Journey
import com.atay.iz.data.JourneyStatus
import com.atay.iz.data.TrackPoint
import com.atay.iz.data.Transport
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: DiaryRepository
    private lateinit var settings: TrackerSettings
    private lateinit var locationClient: FusedLocationProviderClient
    private var filter = PositionFilter()
    private var dwell = DwellDetector()
    private var automaticDistance = AutomaticJourneyDistance()
    private val delivery = LocationRequestState()
    private lateinit var diagnostics: LocationDiagnostics
    private var sessionFilter = PositionFilter()
    private var filteredSessionId: String? = null
    private var sensorManager: SensorManager? = null
    private var walkingListener: SensorEventListener? = null
    private var walkingCounter: WalkingStepCounter? = null
    private var walkingJourneyId: String? = null
    private var destroyed = false
    private var attachedJourneyId: String? = null
    private val queuedStartIds = mutableSetOf<String>()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val id = runningJourneyId
            val session = sessionDemand
            scope.launch {
                TrackingCoordinator.mutex.withLock {
                    if (id == null) {
                        if (session == null || sessionDemand?.id != session.id || runningJourneyId != null) return@withLock
                        if (filteredSessionId != session.id) { sessionFilter = PositionFilter(); filteredSessionId = session.id }
                        val now = System.currentTimeMillis()
                        for (location in result.locations.sortedBy { it.time }) {
                            if (location.time < session.startedAt) continue
                            val accepted = sessionFilter.accept(PositionSample(location.latitude, location.longitude, location.time,
                                if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
                                if (location.hasSpeed()) location.speed else null), now) ?: continue
                            (applicationContext as com.atay.iz.IzApplication).navigation.onAcceptedLocation(session.id,
                                com.atay.iz.navigation.NavigationFix(
                                    com.atay.iz.weather.WeatherCoordinate(accepted.sample.latitude, accepted.sample.longitude),
                                    accepted.sample.time, accepted.sample.accuracy, accepted.sample.speed,
                                    if (location.hasBearing()) location.bearing else null))
                        }
                        return@withLock
                    }
                    var journey = repository.getJourney(id) ?: return@withLock
                    if (runningJourneyId != id || journey.endedAt != null) return@withLock
                    if (journey.status == JourneyStatus.TEMPORARY && suppressesAutomaticRecording) return@withLock
                    val now = System.currentTimeMillis()
                    journey = refreshAutomaticWindow(journey, now) ?: return@withLock
                    if (TrackingPolicy.expired(journey.expiresAt, now)) {
                        expire()
                        return@withLock
                    }
                    for (location in result.locations.sortedBy { it.time }) {
                        if (location.time < journey.startedAt) continue
                        if (TrackingPolicy.automaticCandidateDeadline(journey)?.let { location.time >= it } == true) continue
                        val sample = PositionSample(
                            location.latitude, location.longitude, location.time,
                            if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
                            if (location.hasSpeed()) location.speed.takeIf { it.isFinite() && it >= 0 } else null,
                            if (location.hasAltitude()) location.altitude.takeIf { it.isFinite() } else null)
                        val accepted = filter.accept(sample, now) ?: continue
                        val point = TrackPoint(
                            journeyId = journey.id, latitude = sample.latitude, longitude = sample.longitude,
                            recordedAt = sample.time, accuracy = sample.accuracy, speed = sample.speed,
                            altitude = sample.altitude, breakBefore = accepted.breakBefore)
                        repository.addPoint(point)
                        settings.lastAcceptedLocationAt = point.recordedAt
                        (applicationContext as com.atay.iz.IzApplication).navigation.onAcceptedLocation(journey.id,
                            com.atay.iz.navigation.NavigationFix(
                                com.atay.iz.weather.WeatherCoordinate(point.latitude, point.longitude),
                                point.recordedAt, point.accuracy, point.speed,
                                if (location.hasBearing()) location.bearing.takeIf { it.isFinite() } else null))
                        (applicationContext as com.atay.iz.IzApplication).weatherManager.onAcceptedLocation(
                            journey.id, com.atay.iz.weather.WeatherCoordinate(point.latitude, point.longitude),
                            point.recordedAt, point.accuracy)
                        automaticDistance.observe(point)
                        if (journey.status == JourneyStatus.TEMPORARY && automaticDistance.qualified) {
                            journey = repository.resolveAutomaticCandidate(journey.id, now, restart = settings.enabled) ?: return@withLock
                            if (journey.status != JourneyStatus.CONFIRMED) continue
                            settings.lastDetectionDecision = "500 metre tamamlandı; yolculuk başlangıcından itibaren kaydedildi."
                            updateNotification(journey)
                            TrackingNotifications.prompt(this@TrackingService, journey.id, "journey", "Yolculuğun kaydediliyor",
                                "500 metreyi geçtin. Başlangıç rotan korundu; dur-kalklar aynı yolculukta kalır.")
                        }
                        dwell.observe(sample)
                    }
                }
            }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable)
                settings.lastError = "Konum sinyali bekleniyor. Konum alınamayan bölümler rotada boşluk olarak kalır."
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = DiaryRepository(applicationContext)
        settings = TrackerSettings(applicationContext)
        diagnostics = LocationDiagnostics(java.io.File(noBackupFilesDir, "diagnostics"))
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SensorManager::class.java)
        TrackingNotifications.channels(this)
        try {
            startForeground(TrackingNotifications.RECORDING_ID,
                TrackingNotifications.recording(this, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            isRunning = true
        } catch (_: RuntimeException) {
            settings.lastError = "Konum servisi açılamadı. Konum izinlerini kontrol edip uygulama içinden başlat."
            stopSelf()
            return
        }
        scope.launch {
            while (isActive) {
                delay(15_000)
                TrackingCoordinator.mutex.withLock {
                    val id = runningJourneyId
                    if (id == null) {
                        syncLocationDemand(null)
                        return@withLock
                    }
                    var journey = repository.getJourney(id)
                    if (journey == null || journey.endedAt != null) {
                        if (!TrackingCoordinator.hasPendingManualStartOtherThan(id)) {
                            if (sessionDemand != null) detachDiary(id) else stopSelf()
                        }
                        return@withLock
                    }
                    val now = System.currentTimeMillis()
                    journey = refreshAutomaticWindow(journey, now) ?: return@withLock
                    if (TrackingPolicy.expired(journey.expiresAt, now)) { expire(); return@withLock }
                    if (!TrackingController.hasFineLocation(this@TrackingService)) {
                        repository.markInterrupted(journey.id)
                        settings.lastError = "Konum izni kaldırıldığı için kayıt durduruldu."
                        stopSelf()
                        return@withLock
                    }
                    syncWalkingSensor(journey)
                    syncLocationDemand(journey)
                    updateNotification(journey)
                    if (journey.status == JourneyStatus.CONFIRMED && dwell.shouldPrompt(now, settings.stopMinutes)) {
                        TrackingNotifications.prompt(this@TrackingService, id, "stop", "Bir mola mı verdin?",
                            "Burayı durak olarak ekle, yolculuğu bitir veya devam et.")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedStartId = intent?.takeIf { it.action == ACTION_START }?.getStringExtra("journeyId")
        if (!isRunning) {
            requestedStartId?.let { TrackingCoordinator.clearPendingManualStart(it) }
            return START_NOT_STICKY
        }
        requestedStartId?.let { queuedStartIds.add(it) }
        scope.launch {
            try {
                TrackingCoordinator.mutex.withLock {
                    when (intent?.action) {
                        ACTION_SESSION -> syncLocationDemand(runningJourneyId?.let { repository.getJourney(it) })
                        ACTION_DETACH -> {
                            val id = intent.getStringExtra("journeyId")
                            if (id != null && runningJourneyId == id) {
                                val row = repository.getJourney(id)
                                if (row == null || row.endedAt != null) detachDiary(id)
                            }
                        }
                        ACTION_START -> {
                            requestedStartId?.let {
                                TrackingCoordinator.clearPendingManualStart(it)
                                queuedStartIds.remove(it)
                            }
                            val journey = requestedStartId?.let { repository.getJourney(it) }
                            if (journey == null || journey.endedAt != null || TrackingPolicy.expired(journey.expiresAt, System.currentTimeMillis())) {
                                if (runningJourneyId == null && sessionDemand == null && TrackingCoordinator.pendingManualStartId() == null) stopSelf()
                            } else attach(journey)
                        }
                        ACTION_DETECTED -> {
                            if (suppressesAutomaticRecording || !settings.enabled) {
                                if (runningJourneyId == null && sessionDemand == null && TrackingCoordinator.pendingManualStartId() == null) stopSelf()
                                return@withLock
                            }
                            val suggestion = runCatching { Transport.valueOf(intent.getStringExtra("suggestedTransport") ?: "") }.getOrDefault(Transport.UNKNOWN)
                            var current = repository.activeJourney()
                            if (suppressesAutomaticRecording || !settings.enabled) return@withLock
                            if (current != null && runningJourneyId != current.id &&
                                !TrackingCoordinator.isManualStartPending(current.id)) {
                                repository.markInterrupted(current.id)
                                current = null
                            }
                            if (current == null) {
                                if (suppressesAutomaticRecording || !settings.enabled) return@withLock
                                val journey = repository.createJourney(suggestion, temporary = true)
                                if (suppressesAutomaticRecording || !settings.enabled) {
                                    if (journey.status == JourneyStatus.TEMPORARY) repository.rejectJourney(journey.id)
                                    return@withLock
                                }
                                attach(journey)
                                settings.lastDetectionDecision = "Mesafe ölçülüyor; 500 metre tamamlanınca otomatik kaydedilecek."
                            }
                        }
                        ACTION_REFRESH -> if (runningJourneyId == null) syncLocationDemand(null) else runningJourneyId?.let { id ->
                            repository.getJourney(id)?.let { current ->
                                val journey = refreshAutomaticWindow(current, System.currentTimeMillis()) ?: return@let
                                syncWalkingSensor(journey)
                                syncLocationDemand(journey)
                                updateNotification(journey)
                            }
                        }
                        ACTION_STOP -> {
                            val explicitId = intent.getStringExtra("journeyId")
                            val id = explicitId ?: runningJourneyId
                                ?: TrackingCoordinator.pendingManualStartId()
                            val stopsPendingStart = id?.let { TrackingCoordinator.isManualStartPending(it) } == true
                            // An old diary notification is not authority to stop a live session
                            // after its recorder has been detached, or after a replacement start.
                            if (sessionDemand != null && explicitId != null &&
                                id != runningJourneyId && !stopsPendingStart) return@withLock
                            id?.let { TrackingCoordinator.clearPendingManualStart(it) }
                            val journey = id?.let { repository.getJourney(it) }
                            // A queued Stop can reach us after the diary transaction finished
                            // but before ACTION_DETACH updates the in-memory recorder identity.
                            if (sessionDemand != null && explicitId != null &&
                                (journey == null || journey.endedAt != null)) {
                                if (runningJourneyId == explicitId) detachDiary(explicitId)
                                return@withLock
                            }
                            if (journey != null && journey.endedAt == null) {
                                repository.finishJourney(journey.id)
                                settings.suppressedActivity = settings.currentActivity
                            }
                            if ((id == runningJourneyId || runningJourneyId == null || stopsPendingStart) &&
                                !TrackingCoordinator.hasPendingManualStartOtherThan(id)) stopSelf()
                        }
                        else -> if (runningJourneyId == null && sessionDemand == null && TrackingCoordinator.pendingManualStartId() == null) stopSelf()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                settings.lastError = "Yolculuk kaydı kesildi. Kaydı uygulamadan kontrol et."
                stopSelf()
            }
        }
        // An unobserved gap after process death must not be represented as continuous travel.
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun attach(requestedJourney: Journey) = withContext(NonCancellable) {
        // A recreated service must use the persisted start, never give an old candidate another 15 minutes.
        val journey = if (requestedJourney.status == JourneyStatus.TEMPORARY) {
            repository.resolveAutomaticCandidate(requestedJourney.id, restart = settings.enabled)
        } else requestedJourney
        if (journey == null) { stopSelf(); return@withContext }
        if (destroyed) { repository.markInterrupted(journey.id); return@withContext }
        if (runningJourneyId != journey.id) {
            val points = repository.journeyPoints(journey.id)
            if (destroyed) { repository.markInterrupted(journey.id); return@withContext }
            stopWalkingSensor()
            // Session and diary use one callback. Only the evidence filter changes with the diary identity.
            locationClient.removeLocationUpdates(dwellCallback)
            filter = PositionFilter()
            dwell = DwellDetector()
            automaticDistance = AutomaticJourneyDistance(points)
            attachedJourneyId = journey.id
            runningJourneyId = journey.id
        }
        syncWalkingSensor(journey)
        updateNotification(journey)
        (TrackingPolicy.automaticCandidateDeadline(journey) ?: journey.expiresAt)?.let { ExpirationWorker.at(this@TrackingService, it) }
        ExpirationWorker.schedule(this@TrackingService)
        syncLocationDemand(journey)
    }

    private fun detachDiary(id: String) {
        if (runningJourneyId != id) return
        stopWalkingSensor()
        runningJourneyId = null
        attachedJourneyId = null
        filter = PositionFilter()
        locationClient.removeLocationUpdates(dwellCallback)
        (applicationContext as com.atay.iz.IzApplication).weatherManager.onTrackingStopped(id)
        if (sessionDemand == null) stopSelf() else syncLocationDemand(null)
    }

    /** Replace the callback's cadence only after the previous request has settled. */
    @SuppressLint("MissingPermission")
    private fun syncLocationDemand(journey: Journey?) {
        if (destroyed) return
        val session = sessionDemand
        if (journey == null && session == null) {
            if (TrackingCoordinator.pendingManualStartId() == null) stopSelf()
            return
        }
        if (!TrackingController.hasFineLocation(this)) {
            settings.lastError = "Konum izni gerekli. Konum alımı durdu."
            stopSelf()
            return
        }
        val high = session?.highFrequency == true || navigationJourneyId == journey?.id && journey != null && journey.status == JourneyStatus.CONFIRMED
        val interval = if (high) 1000L else 5000L
        // Notification ownership changes even when cadence does not.
        if (journey == null) updateSessionNotification()
        val requestToken = delivery.request(interval)
        publishDelivery()
        if (requestToken == null) return
        diagnostics.record(LocationDiagnostics.Event.REQUEST, delivery.desiredIntervalMillis, delivery.currentIntervalMillis)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(if (high) 1000 else 3000)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(if (high || journey == null || journey.status == JourneyStatus.TEMPORARY) 0 else 10_000)
            .setWaitForAccurateLocation(false).build()
        fun failed() {
            if (destroyed || !delivery.failed(requestToken)) return
            publishDelivery()
            diagnostics.record(if (delivery.active) LocationDiagnostics.Event.REPLACEMENT_FAILED else LocationDiagnostics.Event.INITIAL_FAILED,
                delivery.desiredIntervalMillis, delivery.currentIntervalMillis)
            settings.lastError = if (delivery.active) "Konum sıklığı güncellenemedi. Önceki GPS isteği korunuyor; yeniden denenecek."
                else "GPS başlatılamadı. Konum hizmetlerini ve izinleri kontrol et."
            // Established recording survives a replacement failure. Initial failure cannot claim delivery.
            if (!delivery.active && journey != null) stopSelf()
        }
        try {
            locationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnSuccessListener {
                    if (!destroyed && delivery.succeeded(requestToken)) {
                        publishDelivery()
                        settings.lastError = null
                        diagnostics.record(LocationDiagnostics.Event.ACKNOWLEDGED, delivery.desiredIntervalMillis, delivery.currentIntervalMillis)
                        if (high || journey == null) locationClient.removeLocationUpdates(dwellCallback)
                        else runCatching { locationClient.requestLocationUpdates(
                            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000)
                                .setMinUpdateIntervalMillis(15_000).setMinUpdateDistanceMeters(0f).build(),
                            dwellCallback, Looper.getMainLooper()) }
                    }
                }.addOnFailureListener { failed() }
        } catch (_: RuntimeException) { failed() }
    }

    private fun publishDelivery() {
        locationDeliveryActive = delivery.active
        currentLocationIntervalMillis = delivery.currentIntervalMillis
        desiredLocationIntervalMillis = delivery.desiredIntervalMillis
        locationRetryPending = delivery.retryPending
    }

    private fun updateSessionNotification() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(this).notify(TrackingNotifications.RECORDING_ID,
                TrackingNotifications.session(this))
        } catch (_: SecurityException) { /* Permission may change after the explicit check. */ }
    }

    /** Keep listening through a reset; continuing movement need not emit another ENTER. */
    private suspend fun refreshAutomaticWindow(journey: Journey, now: Long): Journey? = withContext(NonCancellable) {
        val deadline = TrackingPolicy.automaticCandidateDeadline(journey) ?: return@withContext journey
        if (now < deadline) return@withContext journey
        val resolved = repository.resolveAutomaticCandidate(journey.id, now, restart = settings.enabled)
        if (resolved == null) { stopSelf(); return@withContext null }
        if (destroyed) {
            // onDestroy may have captured the old ID while the replacement transaction committed.
            repository.markInterrupted(resolved.id)
            return@withContext null
        }
        if (resolved.id != journey.id) {
            attach(resolved)
            settings.lastDetectionDecision = "15 dakikada 500 metre tamamlanmadı; mesafe sıfırlandı ve yeni ölçüm başladı."
        }
        if (destroyed) null else resolved
    }

    /** Each registration captures its journey and counter so delayed callbacks cannot cross a mode switch. */
    @SuppressLint("MissingPermission")
    private fun syncWalkingSensor(journey: Journey) {
        if (!canMeasurePhoneSteps(journey, runningJourneyId, hasStepPermission())) {
            stopWalkingSensor()
            return
        }
        if (walkingJourneyId == journey.id && walkingListener != null) return
        stopWalkingSensor()
        val manager = sensorManager ?: return
        try {
            val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                ?: manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
            val counter = WalkingStepCounter(SystemClock.elapsedRealtimeNanos(), journey.stepCount)
            val id = journey.id
            val listener = object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

                override fun onSensorChanged(event: SensorEvent) {
                    // Android reuses SensorEvent and its values array after this callback returns.
                    val value = event.values.firstOrNull() ?: return
                    val timestamp = event.timestamp
                    scope.launch {
                        try {
                            TrackingCoordinator.mutex.withLock {
                                if (walkingCounter !== counter || runningJourneyId != id) return@withLock
                                val current = repository.getJourney(id)
                                if (current == null || !canMeasurePhoneSteps(current, runningJourneyId, hasStepPermission())) {
                                    stopWalkingSensor()
                                    return@withLock
                                }
                                val total = if (sensor.type == Sensor.TYPE_STEP_COUNTER) {
                                    counter.observeCounter(value, timestamp)
                                } else counter.observeDetector(value, timestamp)
                                if (total != null) {
                                    // Finish an accepted DB update before service cleanup can close the journey.
                                    withContext(NonCancellable) { repository.recordWalkingSteps(id, total) }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (walkingCounter === counter) stopWalkingSensor()
                            settings.lastError = "Adım ölçümü durdu. Kaydedilen adımlar korundu."
                        }
                    }
                }
            }
            walkingCounter = counter
            walkingJourneyId = id
            walkingListener = listener
            if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0)) stopWalkingSensor()
        } catch (_: RuntimeException) {
            // Missing hardware or a revoked activity permission must not interrupt GPS recording.
            stopWalkingSensor()
        }
    }

    private fun hasStepPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun stopWalkingSensor() {
        val listener = walkingListener
        walkingListener = null
        walkingCounter = null
        walkingJourneyId = null
        if (listener != null) runCatching { sensorManager?.unregisterListener(listener) }
    }

    private val dwellCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                val sample = PositionSample(location.latitude, location.longitude, location.time,
                    if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
                    if (location.hasSpeed()) location.speed else null)
                if (TrackingPolicy.valid(sample, System.currentTimeMillis())) dwell.observe(sample)
            }
        }
    }

    private suspend fun expire() {
        settings.suppressedActivity = settings.currentActivity
        repository.cleanupExpired()
        stopSelf()
    }

    private fun updateNotification(journey: Journey) {
        if (TrackingNotifications.allowed(this)) {
            try {
                NotificationManagerCompat.from(this).notify(TrackingNotifications.RECORDING_ID,
                    TrackingNotifications.recording(this, journey, automaticDistance.meters))
            } catch (_: SecurityException) { /* Tracking remains visible in Android's active-apps panel. */ }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        delivery.stop()
        publishDelivery()
        diagnostics.record(LocationDiagnostics.Event.STOPPED, null, null)
        val id = attachedJourneyId
        queuedStartIds.forEach { TrackingCoordinator.clearPendingManualStart(it) }
        queuedStartIds.clear()
        id?.let { TrackingCoordinator.clearPendingManualStart(it) }
        (applicationContext as com.atay.iz.IzApplication).weatherManager.onTrackingStopped(id)
        (applicationContext as com.atay.iz.IzApplication).navigation.onTrackingStopped(id)
        if (navigationJourneyId == id) navigationJourneyId = null
        stopWalkingSensor()
        if (runningJourneyId == id) runningJourneyId = null
        isRunning = false
        locationClient.removeLocationUpdates(callback)
        locationClient.removeLocationUpdates(dwellCallback)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (id != null) {
            cleanupScope.launch {
                TrackingCoordinator.mutex.withLock {
                    val journey = repository.getJourney(id)
                    if (journey != null && journey.endedAt == null) {
                        repository.markInterrupted(id)
                        settings.suppressedActivity = settings.currentActivity
                    }
                }
            }
        }
        super.onDestroy()
    }

    companion object {
        private data class SessionDemand(val id: String, val transport: Transport?, val highFrequency: Boolean,
            val suppressAutomatic: Boolean, val startedAt: Long)
        @Volatile private var sessionDemand: SessionDemand? = null
        private var lastSessionStartAttempt = Long.MIN_VALUE
        @Volatile internal var preparingNoRecord = false
        val suppressesAutomaticRecording: Boolean get() = preparingNoRecord || sessionDemand?.suppressAutomatic == true
        @Volatile var locationDeliveryActive = false
            private set
        @Volatile var currentLocationIntervalMillis: Long? = null
            private set
        @Volatile var desiredLocationIntervalMillis: Long? = null
            private set
        @Volatile var locationRetryPending = false
            private set
        internal fun setSession(context: Context, id: String?, transport: Transport?, highFrequency: Boolean, suppressAutomatic: Boolean) {
            val old = sessionDemand
            val next = id?.let { SessionDemand(it, transport, highFrequency, suppressAutomatic,
                old?.takeIf { prior -> prior.id == it }?.startedAt ?: System.currentTimeMillis()) }
            val elapsed = SystemClock.elapsedRealtime()
            if (old == next && (isRunning || next == null ||
                lastSessionStartAttempt != Long.MIN_VALUE && elapsed - lastSessionStartAttempt in 0 until 15_000)) return
            sessionDemand = next
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_SESSION)
            try {
                if (isRunning) context.startService(intent)
                else if (next != null) {
                    lastSessionStartAttempt = elapsed
                    check(TrackingController.hasFineLocation(context))
                    ContextCompat.startForegroundService(context, intent)
                }
            } catch (_: RuntimeException) {
                TrackerSettings(context).lastError = "Canlı konum servisi başlatılamadı. Konum iznini kontrol edip tekrar dene."
            }
        }
        internal fun compatibleSessionTransport(transport: Transport): Boolean =
            sessionDemand?.transport?.let { it == transport } ?: true

        internal fun detachRecording(context: Context, id: String) {
            if (isRunning) runCatching { context.startService(Intent(context, TrackingService::class.java)
                .setAction(ACTION_DETACH).putExtra("journeyId", id)) }.onFailure {
                // The diary transaction already finished. The foreground tick will detach sensors.
                TrackerSettings(context).lastError = "Kayıt bitti; konum servisi durumu yenileniyor."
            }
        }
        private const val ACTION_SESSION = "com.atay.iz.tracking.SESSION"
        private const val ACTION_DETACH = "com.atay.iz.tracking.DETACH"
        const val ACTION_START = "com.atay.iz.tracking.START"
        const val ACTION_DETECTED = "com.atay.iz.tracking.DETECTED"
        const val ACTION_STOP = "com.atay.iz.tracking.STOP"
        const val ACTION_REFRESH = "com.atay.iz.tracking.REFRESH"
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var runningJourneyId: String? = null
            private set
        @Volatile private var navigationJourneyId: String? = null
        internal fun setNavigationJourney(context: Context, id: String?) {
            navigationJourneyId = id
            refresh(context)
        }
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        internal fun refresh(context: Context) {
            if (isRunning) {
                try { context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_REFRESH)) }
                catch (_: RuntimeException) { /* Next foreground tick refreshes the status. */ }
            }
        }
    }
}
