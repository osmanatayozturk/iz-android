package org.iz.navigation.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.iz.navigation.BuildConfig
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.placeinfo.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Only I/O/time/override/action boundaries are synthetic; repository, controller and UI are real.
 * This host does not reproduce map/saved/Nereye routing or actual contribution publication.
 * Each bounded cache is new and retained for diagnosis; no application-data cleanup occurs.
 */
internal class PlaceInformationTestFixtures(initialKey: PlaceInfoKey? = KEY_A) {
    val clock = MutablePlaceInfoClock(Instant.parse("2026-11-12T15:30:00Z"))
    val reader = ControlledPlaceInfoReader()
    val overrides = MemoryPlaceZoneOverrides()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    val cacheDirectory: File = freshCacheDirectory(context)
    val cache = PublicPlaceCache(cacheDirectory, clock, maxObjects = 8, maxBytes = 256L * 1024)
    val repository = PlaceInfoRepository(reader, cache, clock)
    val key = mutableStateOf(initialKey)
    val visible = mutableStateOf(true)
    val mounted = mutableStateOf(true)
    val contributionRevision = mutableStateOf("")
    val latestSession = AtomicReference<PlaceInformationSession?>()
    val intentContext = RecordingPlaceInfoContext(context)
    val externalChoices = CopyOnWriteArrayList<Pair<PlaceInfoAction, Boolean>>()
    val externalErrors = CopyOnWriteArrayList<String>()
    var directionCallbacks = 0
    var surveyCallbacks = 0
    var correctionCallbacks = 0
    var noteCallbacks = 0

    @Composable
    fun Content() {
        if (!mounted.value) return
        val selected = key.value
        val session = rememberPlaceInformationSession(selected, visible.value,
            contributionRevision.value, repository, overrides, clock)
        SideEffect { latestSession.set(session) }
        IzTheme {
            if (selected != null && visible.value) {
                // Production renderer requires the caller's normal vertical scroll container.
                Column(Modifier.fillMaxSize().testTag(SCROLL_TAG).verticalScroll(rememberScrollState())) {
                    PlaceInformationContent(session.state, selected, "Synthetic selection", session.instant,
                        session.deviceZone, session.overrideZoneId, session.changeZone, session.refresh,
                        onDirections = { directionCallbacks++ }, onSurvey = { surveyCallbacks++ },
                        onCorrect = { correctionCallbacks++ }, onNote = { noteCallbacks++ },
                        onExternal = { action, phone ->
                            externalChoices += action to phone
                            openPlaceInformationAction(intentContext, action, phone)?.let(externalErrors::add)
                        }, onClose = { visible.value = false }, modifier = Modifier.fillMaxWidth())
                }
            } else Text("Synthetic host hidden", Modifier.testTag(HIDDEN_TAG))
        }
    }

    fun snapshot(key: PlaceInfoKey = KEY_A, version: Long = 7, name: String = "Synthetic public A",
        tags: Map<String, String> = emptyMap()): PublicPlaceSnapshot = PublicPlaceSnapshot(key, version,
        linkedMapOf("name" to name, "opening_hours" to "24/7", "timezone" to "UTC") + tags,
        clock.millis())

    companion object {
        const val SCROLL_TAG = "place-information-integration-scroll"
        const val HIDDEN_TAG = "place-information-integration-hidden"
        val KEY_A = PlaceInfoKey("https://example.invalid/api/0.6", OsmRef(OsmType.NODE, 1001))
        val KEY_B = PlaceInfoKey("https://example.invalid/api/0.6", OsmRef(OsmType.WAY, 2001))
        val KEY_B_OTHER_ENDPOINT = KEY_B.copy(endpoint = "https://other.invalid/api/0.6")

        /** Format/package guards do not attest source identity; Root must bind the actual APK pair.
         * Missing opt-in is an assumption/NOT_RUN, never a successful physical acceptance.
         */
        fun requireIsolatedAllocation() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Separate Root isolated place-information allocation required",
                args.getString("izPlaceInformationIntegration") == "isolated-offline")
            val expected = checkNotNull(args.getString("izExpectedPackage"))
            check(expected.startsWith("org.iz.navigation.") && expected != "org.iz.navigation")
            val candidate = checkNotNull(args.getString("izCandidateCommit"))
            check(candidate.matches(Regex("[0-9a-f]{40}")))
            val target = instrumentation.targetContext
            check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == expected && target.packageName == expected)
            check(instrumentation.context.packageName == "$expected.test")
            for (pkg in listOf(expected, "$expected.test")) {
                check(target.packageManager.checkPermission(Manifest.permission.INTERNET, pkg) ==
                    PackageManager.PERMISSION_DENIED)
            }
        }

        private fun freshCacheDirectory(context: Context): File {
            val root = File(context.cacheDir.canonicalFile, "place-information-integration-fixtures")
            check(root.canonicalFile == root.absoluteFile)
            check(root.isDirectory || root.mkdir())
            val directory = File(root, UUID.randomUUID().toString())
            check(directory.canonicalFile.parentFile == root.canonicalFile && !directory.exists())
            check(directory.mkdir())
            return directory
        }
    }
}

internal class MutablePlaceInfoClock private constructor(
    private val value: AtomicReference<Instant>, private val clockZone: ZoneId,
) : Clock() {
    constructor(instant: Instant) : this(AtomicReference(instant), ZoneId.of("UTC"))
    override fun getZone(): ZoneId = clockZone
    override fun withZone(zone: ZoneId): Clock = MutablePlaceInfoClock(value, zone)
    override fun instant(): Instant = value.get()
    fun advance(duration: Duration) { value.updateAndGet { it.plus(duration) } }
}

internal class MemoryPlaceZoneOverrides : PlaceZoneOverrides {
    private val values = linkedMapOf<PlaceInfoKey, String>()
    val writes = CopyOnWriteArrayList<Pair<PlaceInfoKey, String?>>()
    @Synchronized override fun read(key: PlaceInfoKey): String? = values[key]
    @Synchronized override fun write(key: PlaceInfoKey, zoneId: String?) {
        zoneId?.let { ZoneId.of(it) }
        if (zoneId == null) values.remove(key) else values[key] = zoneId
        writes += key to zoneId
    }
}

internal class ControlledPlaceInfoReader : PlaceInfoReader {
    class Pending(val key: PlaceInfoKey) {
        val outcome = CompletableDeferred<PlaceInfoRead>()
        val cancellationObserved = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val callerCompleted = AtomicBoolean(false)
        fun found(snapshot: PublicPlaceSnapshot) { check(outcome.complete(PlaceInfoRead.Found(snapshot))) }
        fun unavailable(status: Int) { check(outcome.complete(PlaceInfoRead.Unavailable(key, status))) }
        fun fail(error: Exception) { check(outcome.completeExceptionally(error)) }
    }
    val requests = CopyOnWriteArrayList<Pending>()
    override suspend fun read(key: PlaceInfoKey): PlaceInfoRead {
        check(requests.size < 16) { "Synthetic reader dispatch limit exceeded" }
        val pending = Pending(key)
        checkNotNull(currentCoroutineContext()[Job]).invokeOnCompletion { pending.callerCompleted.set(true) }
        requests += pending
        return try {
            try { pending.outcome.await() }
            catch (cancelled: CancellationException) {
                pending.cancellationObserved.set(true)
                // Deliberately violate cooperative cancellation to prove production ownership guards.
                withContext(NonCancellable) { pending.outcome.await() }
            }
        } finally { pending.finished.set(true) }
    }
    fun abortOutstanding() {
        requests.forEach { it.outcome.completeExceptionally(CancellationException("Synthetic fixture disposed")) }
    }
}

internal class RecordingPlaceInfoContext(base: Context) : ContextWrapper(base) {
    val intents = CopyOnWriteArrayList<Intent>()
    override fun startActivity(intent: Intent) { intents += Intent(intent) }
}
