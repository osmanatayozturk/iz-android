package com.atay.iz.watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WatchHealthOutboxTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun reading(sequence: Long) = HealthReading(sequence, HealthReadingType.HEART_RATE,
        2000 + sequence, 2000 + sequence, 80.0)

    @Test fun reopeningRetainsPendingRowsAndSequenceAfterCommitAcknowledgement() {
        val name = "health-outbox-${UUID.randomUUID()}.db"
        try {
            WatchHealthOutbox(context, name).use {
                it.saveSession(WatchHealthOutbox.Session("s", "trip", 1000, HealthClockAnchor(1000, 500), 4, 0))
                it.add("s", reading(1), 3000); it.add("s", reading(2), 3001)
                it.acknowledge(HealthAck("s", 1, false))
            }
            WatchHealthOutbox(context, name).use {
                assertEquals(listOf(reading(2)), it.next("watch")!!.readings)
                assertEquals(2L, it.session("s")!!.sequence)
                it.acknowledge(HealthAck("s", 2, false))
                assertNull(it.next("watch"))
                assertEquals(2L, it.session("s")!!.sequence)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun boundedBatchAndTerminalAcknowledgementNeverDeleteAnotherSession() {
        val name = "health-outbox-${UUID.randomUUID()}.db"
        try {
            WatchHealthOutbox(context, name).use {
                for (id in listOf("one", "two")) {
                    it.saveSession(WatchHealthOutbox.Session(id, "trip", 1000, HealthClockAnchor(1000, 500), 4, 0))
                    for (sequence in 1L..55) it.add(id, reading(sequence), if (id == "one") 3000 else 4000)
                }
                assertEquals(50, it.next("watch")!!.readings.size)
                it.acknowledge(HealthAck("one", 50, true))
                assertEquals(55, it.count())
                assertEquals("two", it.next("watch")!!.sessionId)
                it.clear(); assertEquals(0, it.count()); assertNull(it.session("two"))
            }
        } finally { context.deleteDatabase(name) }
    }
}
