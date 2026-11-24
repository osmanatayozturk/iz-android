package org.iz.navigation.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.model.*
import kotlinx.coroutines.*
import org.iz.navigation.garage.FuelOffer

internal enum class CarFuelAlertCancel { USER, TIMEOUT, UNSUPPORTED }
internal interface CarFuelAlertHost {
    fun show(offer: FuelOffer, remainingMs: Long, accept: () -> Unit, reject: () -> Unit,
        cancelled: (CarFuelAlertCancel) -> Unit)
    fun dismiss()
}

/** Host callbacks belong to one visible lease, including when the same offer returns from a menu. */
internal class CarFuelAlerts(private val scope: CoroutineScope, private val now: () -> Long,
    private val visible: () -> Boolean, private val current: () -> FuelOffer?,
    private val host: CarFuelAlertHost, private val accept: suspend (String) -> Boolean,
    private val reject: (String) -> Unit, private val failed: () -> Unit) {
    private var shown: FuelOffer? = null
    private var generation = 0L
    private var busy = false
    private var suppressed: String? = null

    fun update() {
        val offer = current()
        if (!visible() || offer == null || now() >= offer.expiresAtElapsedMs) {
            hide()
            if (!visible() || offer == null) suppressed = null
            return
        }
        if (shown?.id == offer.id || suppressed == offer.id) return
        hide()
        shown = offer
        val token = ++generation
        fun valid() = token == generation && shown?.id == offer.id &&
            carFuelActionAllowed(offer.id, current()?.id, offer.expiresAtElapsedMs, now(), visible(), busy)
        try {
            host.show(offer, offer.expiresAtElapsedMs - now(), accept = {
                if (valid()) {
                    busy = true
                    scope.launch {
                        try { if (!accept(offer.id)) failed() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed() }
                        finally { if (token == generation) busy = false }
                    }
                }
            }, reject = { if (valid()) reject(offer.id) }, cancelled = { reason ->
                if (token == generation) {
                    if (reason == CarFuelAlertCancel.USER && valid()) reject(offer.id)
                    else { suppressed = offer.id; hide() }
                }
            })
        } catch (_: Exception) { suppressed = offer.id; hide() }
    }

    fun hide() {
        generation++
        busy = false
        if (shown != null) { shown = null; runCatching { host.dismiss() } }
    }
}

internal class NativeCarFuelAlertHost(private val context: CarContext) : CarFuelAlertHost {
    private companion object { val ids = java.util.concurrent.atomic.AtomicInteger() }
    private var shownId: Int? = null
    override fun show(offer: FuelOffer, remainingMs: Long, accept: () -> Unit, reject: () -> Unit,
        cancelled: (CarFuelAlertCancel) -> Unit) {
        val id = ids.incrementAndGet()
        val detail = buildString {
            append(offer.title.take(150))
            if (offer.distanceToStationMeters.isFinite() && offer.distanceToStationMeters >= 0)
                append(" · ${km(offer.distanceToStationMeters)} km")
            offer.additionalSeconds?.takeIf { it.isFinite() && it >= 0 }?.let {
                append(" · +${kotlin.math.ceil(it / 60).toInt()} dk")
            }
        }
        val alert = Alert.Builder(id, CarText.create("Yakıt istasyonu"), remainingMs)
            .setSubtitle(CarText.create(detail))
            .addAction(Action.Builder().setTitle("Kabul et").setOnClickListener { accept() }.build())
            .addAction(Action.Builder().setTitle("Reddet").setOnClickListener { reject() }.build())
            .setCallback(object : AlertCallback {
                override fun onCancel(reason: Int) = cancelled(when (reason) {
                    AlertCallback.REASON_USER_ACTION -> CarFuelAlertCancel.USER
                    AlertCallback.REASON_TIMEOUT -> CarFuelAlertCancel.TIMEOUT
                    else -> CarFuelAlertCancel.UNSUPPORTED
                })
                override fun onDismiss() = Unit
            }).build()
        shownId = id
        context.getCarService(AppManager::class.java).showAlert(alert)
    }
    override fun dismiss() {
        val id = shownId ?: return
        shownId = null
        context.getCarService(AppManager::class.java).dismissAlert(id)
    }
}
