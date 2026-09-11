package org.iz.navigation.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.data.HealthMetric
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthDiaryReplacementTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DiaryRepository(context)
    private val prefs = context.getSharedPreferences("health_connect_sync_v1", Context.MODE_PRIVATE)

    @Before fun setup() = runBlocking {
        assertTrue(prefs.edit().clear().putBoolean("enabled", false).commit())
        repository.restore(DiarySnapshot())
    }
    @After fun cleanup() = runBlocking {
        prefs.edit().clear().putBoolean("enabled", false).commit()
        repository.restore(DiarySnapshot())
    }

    @Test fun cancellationAfterDatabaseReplacementCannotPreserveOldHealthCheckpoints() = runBlocking {
        val before = Journey(id = "before", startedAt = 1_000, endedAt = 2_000)
        val replacement = Journey(id = "replacement", transport = Transport.RUN,
            startedAt = 3_000, endedAt = 4_000, stepCount = 17)
        repository.restore(DiarySnapshot(journeys = listOf(before)))
        // Never initialize this coordinator: no HC grant or background read is needed.
        val manager = HealthConnectManager(context, repository)
        val editor = prefs.edit().putBoolean("enabled", false).putLong("lastSync", 8_000).putLong("historyStart", 500)
        HealthMetric.entries.forEach {
            editor.putString("cursor:${it.name}", "previous-cursor")
            editor.putString("trip:${it.name}:${before.id}", "previous-stamp")
        }
        assertTrue(editor.commit())
        assertTrue(prefs.contains("lastSync"))
        val expected = CancellationException("Cancel after committed diary replacement")
        var cancelled = false
        try {
            manager.withDiaryReplacement {
                // Invalidation must precede the first instruction of the replacement callback.
                assertFalse(prefs.contains("lastSync"))
                assertTrue(prefs.all.keys.none { it.startsWith("cursor:") || it.startsWith("trip:") })
                repository.restore(DiarySnapshot(journeys = listOf(replacement)))
                throw expected
            }
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
            cancelled = true
        }

        assertTrue("Cancellation must propagate to the caller", cancelled)
        assertEquals(listOf(replacement), repository.snapshot().journeys)
        assertFalse(prefs.contains("lastSync"))
        assertTrue(prefs.all.keys.none { it.startsWith("cursor:") || it.startsWith("trip:") })
        assertFalse(prefs.getBoolean("enabled", true))
        assertEquals(500L, prefs.getLong("historyStart", -1))
    }
}
