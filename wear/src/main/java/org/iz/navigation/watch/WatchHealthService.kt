package org.iz.navigation.watch

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.iz.navigation.wearprotocol.*
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

internal data class WatchLiveHealthState(val armed: Boolean = false, val onBody: Boolean? = null,
    val journeyId: String? = null, val capturing: Boolean = false, val latestHeartRate: Double? = null,
    val latestHeartAt: Long? = null, val steps: Long? = null, val buffered: Int = 0,
    val status: String = "Otomatik saat ölçümü kapalı")

internal object WatchHealthRuntime {
    var running = false
    val mutableState = MutableStateFlow(WatchLiveHealthState())
    val state = mutableState.asStateFlow()
    fun prefs(context: Context) = context.getSharedPreferences("watch_health_v1", Context.MODE_PRIVATE)
    fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    fun has(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun permissions(context: Context): Boolean = WatchHealthPermissions.hasRequired(Build.VERSION.SDK_INT) { has(context, it) }
    fun arm(context: Context) {
        require(permissions(context)) { "Saat sensörü ve arka plan izinleri gerekli." }
        prefs(context).edit().putBoolean("armed", true).commit()
        resume(context)
    }
    fun resume(context: Context) {
        val armed = prefs(context).getBoolean("armed", false)
        if (armed && !permissions(context)) {
            mutableState.value = WatchLiveHealthState(armed = true, status = "Saat sensörü ve her zaman arka plan izinlerini kontrol et.")
            return
        }
        if (!WatchHealthResumePolicy.shouldResume(armed, permissions(context), running)) return
        try { ContextCompat.startForegroundService(context, Intent(context, WatchHealthService::class.java)) }
        catch (_: Exception) { mutableState.value = WatchLiveHealthState(armed = true, status = "Ölçümü sürdürmek için saatte İz'i aç.") }
    }
    fun disarm(context: Context) {
        prefs(context).edit().putBoolean("armed", false).commit()
        context.stopService(Intent(context, WatchHealthService::class.java))
        mutableState.value = WatchLiveHealthState()
    }
    fun rebindPhone(context: Context) {
        if (running) context.startService(Intent(context, WatchHealthService::class.java).setAction(WatchHealthService.ACTION_REBIND))
        else {
            WatchHealthOutbox(context).use { it.clear() }
            prefs(context).edit().remove("phoneId").remove("sessionId").commit()
            resume(context)
        }
    }
}

/** Started once from visible WatchActivity; later trips enable listeners inside this existing health FGS. */
class WatchHealthService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sensors: SensorManager
    private lateinit var outbox: WatchHealthOutbox
    private val capture = WatchHealthCapture()
    private var heartSensor: Sensor? = null
    private var stepSensor: Sensor? = null
    private var bodySensor: Sensor? = null
    private var observing = false
    private var collecting = false
    private val sensorListeners = mutableMapOf<WatchHealthSensorController.Kind, SensorEventListener>()
    private fun sensor(kind: WatchHealthSensorController.Kind): Sensor? = when (kind) {
        WatchHealthSensorController.Kind.BODY -> bodySensor
        WatchHealthSensorController.Kind.HEART -> heartSensor
        WatchHealthSensorController.Kind.STEPS -> stepSensor
    }
    private val sensorController = WatchHealthSensorController(capture, object : WatchHealthSensorController.Hardware {
        override fun available(kind: WatchHealthSensorController.Kind) = sensor(kind) != null
        override fun register(kind: WatchHealthSensorController.Kind, generation: Long): Boolean {
            // A new listener object carries its registration identity even if Android delivers a queued callback.
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) = receiveSensor(kind, generation, event)
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            val registered = sensors.registerListener(listener, sensor(kind)!!,
                if (kind == WatchHealthSensorController.Kind.HEART) 1_000_000 else SensorManager.SENSOR_DELAY_NORMAL)
            if (registered) sensorListeners[kind] = listener
            return registered
        }
        override fun unregister(kind: WatchHealthSensorController.Kind) {
            sensorListeners.remove(kind)?.let { sensors.unregisterListener(it) }
        }
    }) { elapsed -> anchor?.let { capture.resumeAt(it.epochAt(elapsed)) } }
    private var dataListening = false
    private var messageListening = false
    private var capabilityListening = false
    private var lock: PowerManager.WakeLock? = null
    private var phone: String? = null
    private var anchor: HealthClockAnchor? = null
    private var pendingHello: Pair<String, Long>? = null
    private var lastHello = -30_000L
    private var lastStateTime = 0L
    private var stateKey: String? = null
    private var heartbeat: Job? = null
    private var sending: Job? = null
    private var pendingBatch: Pair<String, Long>? = null
    private var inflightBatch: HealthBatch? = null
    private var stepsTotal: Long? = null
    private val prefs by lazy { WatchHealthRuntime.prefs(this) }
    private val watchId by lazy { prefs.getString("watchId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("watchId", it).commit() } }
    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(this) }
    private val dataListener = DataClient.OnDataChangedListener { events -> events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach { event ->
        val item = event.dataItem
        if (item.uri.path == WearProtocol.V4_STATE_PATH) {
            val bytes = runCatching { DataMapItem.fromDataItem(item).dataMap.getByteArray(WearProtocol.STATE_KEY) }.getOrNull()
            val source = item.uri.host
            if (bytes != null && source != null) scope.launch { receiveState(source, bytes) }
        }
    } }
    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path == WatchHealthProtocol.PATH) { val bytes = event.data.copyOf(); val source = event.sourceNodeId
            scope.launch { receive(source, bytes) } }
    }
    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { scope.launch { discover() } }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SensorManager::class.java)
        outbox = WatchHealthOutbox(this)
        fun sensor(type: Int) = sensors.getDefaultSensor(type, true) ?: sensors.getDefaultSensor(type)
        heartSensor = sensor(Sensor.TYPE_HEART_RATE); stepSensor = sensor(Sensor.TYPE_STEP_COUNTER)
        bodySensor = sensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Iz:WatchHealth").apply { setReferenceCounted(false) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISARM) { WatchHealthRuntime.disarm(this); return START_NOT_STICKY }
        if (intent?.action == ACTION_REBIND) {
            sending?.cancel(); sending = null; inflightBatch = null; pendingBatch = null
            detach(); outbox.clear(); phone = null; pendingHello = null; stateKey = null; lastStateTime = 0
            prefs.edit().remove("phoneId").remove("sessionId").commit()
            if (observing) scope.launch { discover() }
        }
        if (!prefs.getBoolean("armed", false) || !WatchHealthRuntime.permissions(this)) {
            detach()
            WatchHealthRuntime.mutableState.value = WatchLiveHealthState(armed = prefs.getBoolean("armed", false), status = "Saat sensörü ve arka plan izinlerini kontrol et.")
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Otomatik saat sağlık takibi", NotificationManager.IMPORTANCE_LOW))
            val notification = notification("Kayıt yok · Sensörler kapalı")
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            else startForeground(NOTIFICATION, notification)
        } catch (_: Exception) {
            WatchHealthRuntime.mutableState.value = WatchLiveHealthState(armed = true, status = "Ölçümü sürdürmek için saatte İz'i aç.")
            stopSelf(); return START_NOT_STICKY
        }
        WatchHealthRuntime.running = true
        if (!observing) {
            observing = true
            WatchHealthRuntime.mutableState.value = WatchLiveHealthState(armed = true, status = "Kayıt yok · Sensörler kapalı")
            updateCollection()
            startHeartbeat()
            scope.launch {
                try {
                    ensureListeners()
                    val removed = outbox.prune(System.currentTimeMillis())
                    if (removed > 0) status("Eski bekleyen ölçümler silindi; kayıtta boşluk olabilir")
                    discover()
                } catch (_: CancellationException) { throw CancellationException() }
                catch (_: Exception) { status("Saat–telefon bağlantısı bekleniyor") }
            }
        }
        return START_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 80, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 81, Intent(this, WatchHealthService::class.java).setAction(ACTION_DISARM), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(org.iz.navigation.watch.R.drawable.ic_watch)
            .setContentTitle(if (collecting) "İz yolculuğu ölçüyor" else "İz yolculuğa hazır").setContentText(text).setOngoing(true)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Kapat", stop).build()).build()
    }
    private fun status(text: String) { WatchHealthRuntime.mutableState.value = WatchHealthRuntime.mutableState.value.copy(status = text)
        if (observing) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text)) }

    private suspend fun ensureListeners() {
        withTimeout(10_000) {
            if (!dataListening) { dataClient.addListener(dataListener).await(); dataListening = true }
            if (!messageListening) { messageClient.addListener(messageListener).await(); messageListening = true }
            if (!capabilityListening) { capabilityClient.addListener(capabilityListener, WearProtocol.V4_PHONE_CAPABILITY).await(); capabilityListening = true }
        }
    }

    private suspend fun discover() {
        try {
            ensureListeners()
            val nodes = withTimeout(10_000) { capabilityClient.getCapability(WearProtocol.V4_PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes }
            val boundPhone = prefs.getString("phoneId", null)
            // Pending measurements belong to one phone installation. Never redirect its queue to a different phone.
            val selected = if (boundPhone != null) nodes.firstOrNull { it.id == boundPhone }
                else nodes.sortedWith(compareByDescending<Node> { it.isNearby }.thenBy { it.id }).firstOrNull()
            if (boundPhone == null && selected != null) prefs.edit().putString("phoneId", selected.id).commit()
            if (phone != selected?.id) { phone = selected?.id; pendingHello = null; stateKey = null; lastStateTime = 0 }
            if (phone == null) { status("Güncel telefon bağlantısı bekleniyor"); return }
            hello(force = true); sendNext()
        } catch (_: Exception) { status("Telefon bağlantısı bekleniyor") }
    }
    private suspend fun hello(force: Boolean = false) {
        val target = phone ?: return
        val elapsed = SystemClock.elapsedRealtime()
        if (!force && elapsed - lastHello < 5_000) return
        val id = UUID.randomUUID().toString(); lastHello = elapsed; pendingHello = id to elapsed
        val session = capture.sessionId ?: prefs.getString("sessionId", null)?.takeIf {
            outbox.session(it)?.let { stored -> WatchHealthResumePolicy.canReuseClock(stored.boot, WatchHealthRuntime.boot(this)) } == true }
        try { withTimeout(10_000) { messageClient.sendMessage(target, WatchHealthProtocol.PATH, WatchHealthProtocol.encode(
            HealthHello(id, watchId, Build.MODEL.take(200), session, capture.worn, collecting, outbox.count().coerceAtMost(100_000)))).await() } }
        catch (_: Exception) { status("Telefon yanıtı bekleniyor; ölçümler saatte saklanır") }
    }
    private suspend fun receiveState(source: String, bytes: ByteArray) {
        if (source != phone) return
        val value = WearProtocol.decodeSnapshot(bytes) ?: return
        val now = anchor?.epochAt(SystemClock.elapsedRealtime()) ?: System.currentTimeMillis()
        if (!WearProtocol.isFreshSnapshot(value, now) || value.generatedAt <= lastStateTime) return
        lastStateTime = value.generatedAt
        val key = "${value.journeyId}:${value.recording}:${value.temporary}"
        if (stateKey != key) {
            stateKey = key
            if (value.journeyId != capture.journeyId || !value.recording || value.temporary) detach()
            hello(force = true)
        }
    }
    private suspend fun receive(source: String, bytes: ByteArray) {
        if (source != phone || !observing) return
        // Expiry invalidates the in-flight HELLO too; its delayed reply cannot revive old worn state.
        if (capture.expired(SystemClock.elapsedRealtime())) detach()
        if (!WatchHealthRuntime.permissions(this)) { detach(); return }
        when (val value = WatchHealthProtocol.decode(bytes)) {
            is HealthHelloReply -> {
                val request = pendingHello?.takeIf { it.first == value.requestId } ?: return
                pendingHello = null
                val clock = HealthClockAnchor.create(value.phoneAt, request.second, SystemClock.elapsedRealtime()) ?: return
                val sessionId = value.sessionId
                if (sessionId == null) { detach(); return }
                val old = outbox.session(sessionId)
                if (old != null && !WatchHealthResumePolicy.canReuseClock(old.boot, WatchHealthRuntime.boot(this))) {
                    prefs.edit().remove("sessionId").commit(); detach(); hello(force = true); return
                }
                if (old != null && old.anchor.discontinuous(clock, SystemClock.elapsedRealtime())) {
                    // Never join a step interval across a phone wall-clock correction. Old batches keep their original times.
                    prefs.edit().remove("sessionId").commit(); detach(); hello(force = true); return
                }
                val stored = old ?: WatchHealthOutbox.Session(sessionId, value.journeyId!!, value.createdAt!!, clock, WatchHealthRuntime.boot(this), 0).also(outbox::saveSession)
                if (capture.sessionId != stored.id) { detach(); stepsTotal = null; WatchHealthRuntime.mutableState.value = WatchHealthRuntime.mutableState.value.copy(latestHeartRate = null, latestHeartAt = null, steps = null) }
                anchor = stored.anchor; prefs.edit().putString("sessionId", stored.id).commit()
                capture.attach(stored.id, stored.journeyId, stored.createdAt, SystemClock.elapsedRealtime() + WatchHealthProtocol.LEASE_MILLIS, stored.sequence)
                updateCollection(); startHeartbeat(); sendNext()
            }
            is HealthAck -> {
                val pending = pendingBatch ?: return
                if (value.sessionId != pending.first || value.throughSequence != pending.second) return
                outbox.acknowledge(value); pendingBatch = null; inflightBatch = null
                if (value.terminal && capture.sessionId == value.sessionId) { prefs.edit().remove("sessionId").commit(); detach(); hello(force = true) }
                updateState(); sendNext()
            }
            else -> Unit
        }
    }
    private fun startHeartbeat() {
        if (heartbeat?.isActive == true) return
        heartbeat = scope.launch {
            while (isActive && observing) {
                delay(5_000)
                if (!WatchHealthRuntime.permissions(this@WatchHealthService)) { detach(); stopSelf(); break }
                if (capture.expired(SystemClock.elapsedRealtime())) { detach(); status("Telefon doğrulaması kesildi; ölçüm duraklatıldı") }
                updateCollection()
                if (SystemClock.elapsedRealtime() - lastHello >= 30_000) {
                    if (phone == null || !dataListening || !messageListening || !capabilityListening) discover() else hello()
                }
                sendNext(); updateState()
            }
        }
    }
    private fun updateCollection() {
        val allowed = observing && WatchHealthRuntime.permissions(this)
        val previousSession = capture.sessionId
        sensorController.update(allowed, SystemClock.elapsedRealtimeNanos())
        if (previousSession != null && capture.sessionId == null) clearSessionState()
        collecting = sensorController.collecting
        val heartRegistered = sensorController.registered(WatchHealthSensorController.Kind.HEART)
        val stepRegistered = sensorController.registered(WatchHealthSensorController.Kind.STEPS)
        val bodyRegistered = sensorController.registered(WatchHealthSensorController.Kind.BODY)
        if (collecting && (heartRegistered && heartSensor?.isWakeUpSensor == false ||
            stepRegistered && stepSensor?.isWakeUpSensor == false || bodySensor?.isWakeUpSensor == false)) lock?.acquire(60_000)
        else if (lock?.isHeld == true) lock?.release()
        val detail = when {
            capture.sessionId == null -> "Kayıt yok · Sensörler kapalı"
            bodySensor == null -> "Bilekte olma sensörü bulunamadı; canlı ölçüm kapalı"
            !bodyRegistered -> "Bilekte olma sensörü açılamadı; yeniden denenecek"
            capture.worn == false -> "Saat bilekte değil; ölçüm duraklatıldı"
            capture.worn != true -> "Bilekte olma doğrulanıyor"
            heartSensor == null && stepSensor == null -> "Nabız ve adım sensörü bulunamadı; canlı ölçüm yok"
            !collecting -> "Saat sensörleri açılamadı; yeniden denenecek"
            !heartRegistered -> if (heartSensor == null) "Adımlar ölçülüyor; nabız sensörü bulunamadı" else "Adımlar ölçülüyor; nabız sensörü yeniden denenecek"
            !stepRegistered -> if (stepSensor == null) "Nabız ölçülüyor; adım sensörü bulunamadı" else "Nabız ölçülüyor; adım sensörü yeniden denenecek"
            else -> "Yolculuk ölçülüyor"
        }
        if (WatchHealthRuntime.mutableState.value.status != detail) status(detail)
        updateState()
    }
    private fun updateState() {
        WatchHealthRuntime.mutableState.value = WatchHealthRuntime.mutableState.value.copy(armed = observing,
            onBody = capture.worn, journeyId = capture.journeyId, capturing = collecting, steps = stepsTotal, buffered = outbox.count())
    }
    private fun clearSessionState() {
        pendingHello = null; anchor = null; stepsTotal = null
        prefs.edit().remove("sessionId").commit()
        WatchHealthRuntime.mutableState.value = WatchHealthRuntime.mutableState.value.copy(latestHeartRate = null, latestHeartAt = null, steps = null)
    }
    private fun detach() { capture.detach(); clearSessionState(); updateCollection() }
    private fun receiveSensor(kind: WatchHealthSensorController.Kind, generation: Long, event: SensorEvent) {
        if (!observing) return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (!WatchHealthRuntime.permissions(this) || capture.expired(nowNanos / 1_000_000)) {
            detach(); return
        }
        if (kind == WatchHealthSensorController.Kind.BODY) {
            if (sensorController.onBody(generation, event.timestamp, nowNanos,
                when (event.values.firstOrNull()) { 1f -> true; 0f -> false; else -> null })) updateCollection()
            return
        }
        if (!sensorController.accept(kind, generation, event.timestamp, nowNanos)) return
        val clock = anchor ?: return
        val now = nowNanos / 1_000_000
        val eventElapsed = event.timestamp / 1_000_000
        val measuredAt = clock.epochAt(eventElapsed)
        val reading = when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> capture.heart(event.values.firstOrNull()?.toDouble() ?: return, event.accuracy, measuredAt, now)
            Sensor.TYPE_STEP_COUNTER -> capture.steps(event.values.firstOrNull()?.toDouble() ?: return, measuredAt, now)
            else -> null
        } ?: return
        try {
            outbox.add(capture.sessionId ?: return, reading, System.currentTimeMillis())
            if (reading.sequence % 100L == 0L) outbox.prune(System.currentTimeMillis())
            if (reading.type == HealthReadingType.HEART_RATE) WatchHealthRuntime.mutableState.value = WatchHealthRuntime.mutableState.value.copy(
                latestHeartRate = reading.value, latestHeartAt = reading.startAt)
            else stepsTotal = (stepsTotal ?: 0) + reading.value.toLong()
            updateState()
        } catch (_: Exception) { detach(); status("Saat depolamasına yazılamadı; ölçüm durduruldu") }
    }
    private fun sendNext() {
        if (sending?.isActive == true) return
        val target = phone ?: return
        sending = scope.launch {
            try {
                val batch = inflightBatch ?: (outbox.next(watchId) ?: return@launch).also { inflightBatch = it }
                pendingBatch = batch.sessionId to batch.readings.last().sequence
                withTimeout(10_000) { messageClient.sendMessage(target, WatchHealthProtocol.PATH, WatchHealthProtocol.encode(batch)).await() }
            } catch (_: Exception) { /* Durable queue retries on heartbeat or reconnection. */ }
            finally {
                sending = null
                // An ACK can arrive while sendMessage's Task is still completing.
                if (pendingBatch == null && outbox.count() > 0) scope.launch { delay(100); sendNext() }
            }
        }
    }
    override fun onDestroy() {
        observing = false; capture.detach(); sensorController.update(false, SystemClock.elapsedRealtimeNanos()); if (lock?.isHeld == true) lock?.release()
        dataClient.removeListener(dataListener); messageClient.removeListener(messageListener)
        capabilityClient.removeListener(capabilityListener, WearProtocol.V4_PHONE_CAPABILITY)
        scope.cancel(); outbox.close()
        val wasRunning = WatchHealthRuntime.running
        WatchHealthRuntime.running = false
        WatchHealthRuntime.mutableState.value = if (prefs.getBoolean("armed", false)) WatchLiveHealthState(armed = true,
            status = if (wasRunning) "Ölçümü sürdürmek için saatte İz'i aç." else WatchHealthRuntime.mutableState.value.status)
            else WatchLiveHealthState()
        super.onDestroy()
    }
    companion object { private const val CHANNEL = "watch_health"; private const val NOTIFICATION = 4100; private const val ACTION_DISARM = "org.iz.navigation.DISARM_HEALTH"
        internal const val ACTION_REBIND = "org.iz.navigation.REBIND_HEALTH_PHONE" }
}
