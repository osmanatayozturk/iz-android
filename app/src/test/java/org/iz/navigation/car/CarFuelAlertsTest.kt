package org.iz.navigation.car

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.iz.navigation.garage.FuelOffer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CarFuelAlertsTest {
    private class Host : CarFuelAlertHost {
        data class Entry(val id: String, val remaining: Long, val accept: () -> Unit,
            val reject: () -> Unit, val cancel: (CarFuelAlertCancel) -> Unit)
        val shown = mutableListOf<Entry>()
        var dismissed = 0
        override fun show(offer: FuelOffer, remainingMs: Long, accept: () -> Unit, reject: () -> Unit,
            cancelled: (CarFuelAlertCancel) -> Unit) { shown += Entry(offer.id, remainingMs, accept, reject, cancelled) }
        override fun dismiss() { dismissed++ }
    }
    @Test fun menuReturnUsesRemainingDeadlineAndDiscardsTheOldNativeCallbacks() = runTest {
        var now = 0L
        var visible = true
        var offer: FuelOffer? = FuelOffer("one", "Station", 20_000, 200.0, 30.0)
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val host = Host()
        val owner = CarFuelAlerts(this, { now }, { visible }, { offer }, host,
            { accepted += it; offer = null; true }, { rejected += it; offer = null }, { fail("Unexpected failure") })
        owner.update(); owner.update()
        val old = host.shown.single()
        assertEquals(20_000L, old.remaining)
        visible = false; now = 5_000; owner.update()
        assertEquals(1, host.dismissed)
        visible = true; now = 8_000; owner.update()
        assertEquals(12_000L, host.shown.last().remaining)
        old.accept(); old.reject(); old.cancel(CarFuelAlertCancel.USER)
        runCurrent()
        assertTrue(accepted.isEmpty()); assertTrue(rejected.isEmpty())
        host.shown.last().accept(); host.shown.last().accept()
        runCurrent()
        assertEquals(listOf("one"), accepted)
        owner.update()
        assertEquals(2, host.dismissed)
    }
    @Test fun nativeTimeoutAndCentralExpiryNeverAcceptOrRejectARoute() = runTest {
        var now = 0L
        var offer: FuelOffer? = FuelOffer("one", "Station", 20_000, 200.0, null)
        var effects = 0
        val host = Host()
        val owner = CarFuelAlerts(this, { now }, { true }, { offer }, host,
            { effects++; true }, { effects++ }, { effects++ })
        owner.update()
        val callback = host.shown.single()
        now = 20_000
        callback.cancel(CarFuelAlertCancel.TIMEOUT)
        callback.accept(); callback.reject(); owner.update()
        offer = null; owner.update()
        runCurrent()
        assertEquals(0, effects)
        assertEquals(1, host.shown.size)
        assertEquals(1, host.dismissed)
    }
    @Test fun rejectionClearsOnlyTheCurrentOfferAndHiddenActionsCannotRun() = runTest {
        var visible = true
        var offer: FuelOffer? = FuelOffer("one", "Station", 20_000, 200.0, null)
        val rejected = mutableListOf<String>()
        val host = Host()
        val owner = CarFuelAlerts(this, { 0 }, { visible }, { offer }, host,
            { fail("Rejection cannot accept"); false }, { rejected += it; offer = null }, { fail("Unexpected failure") })
        owner.update()
        val old = host.shown.single()
        offer = offer!!.copy(id = "two"); owner.update()
        old.reject(); old.cancel(CarFuelAlertCancel.USER)
        visible = false
        host.shown.last().accept(); host.shown.last().reject()
        assertTrue(rejected.isEmpty())
        visible = true
        host.shown.last().reject()
        owner.update(); runCurrent()
        assertEquals(listOf("two"), rejected)
        assertNull(offer)
    }
}
