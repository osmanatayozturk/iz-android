package org.iz.navigation.car

import androidx.car.app.CarContext
import androidx.car.app.model.*
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.GarageVehicle

internal class CarVehicleScreen(context: CarContext, private val transport: Transport,
    private val expected: CarVehicleStartSnapshot, private val parkedOnly: Boolean = false,
    private val journeyId: String? = null,
    private val operation: suspend (String?, () -> Boolean) -> Unit) : CarScreen(context) {
    private var vehicles = emptyList<GarageVehicle>()
    private var loading = true
    private var stale = false
    private var busy = false
    private var error: String? = null
    private var page = 0
    private var assignedId: String? = null
    init { scope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (journeyId != null) launch { app.garage.assignedVehicleId(journeyId).collect {
                assignedId = it; safeInvalidate()
            } }
            try {
                combine(app.garage.vehicles, navigation.state) { garage, state ->
                    garage.filter { it.transport == transport } to (expected == carVehicleStartSnapshot(state, transport))
                }.collect { (choices, current) ->
                    vehicles = choices; stale = !current; loading = false; safeInvalidate()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { loading = false; error = failure.message ?: "Araçlar okunamadı."; safeInvalidate() }
        }
    } }
    override fun carTemplate(): Template {
        val limit = listLimit()
        val lastPage = (vehicles.size - 1).coerceAtLeast(0) / limit
        page = page.coerceIn(0, lastPage)
        val detail = error ?: when {
            stale -> "Yolculuk değişti. Geri dönüp yeniden seçin."
            busy -> "Başlatılıyor…"
            else -> "Bu türde araç yok. Garajı telefonda düzenleyin."
        }
        val list = ListTemplate.Builder().setTitle(if (parkedOnly) "Yolculuğa araç ata" else "Bu sürüşteki araç")
            .setHeaderAction(Action.BACK).setLoading(loading).apply {
                if (!loading) setSingleList(ItemList.Builder().setNoItemsMessage(detail).apply {
                    vehicles.drop(page * limit).take(limit).forEach { vehicle ->
                        addItem(Row.Builder().setTitle(vehicle.name.take(150))
                            .addText(if (stale) detail else if (busy) "Başlatılıyor…" else error ?: if (vehicle.id == assignedId)
                                "Bu yolculuğa atanmış" else "${vehicle.capacityLitres} litre depo")
                            .setOnClickListener(if (parkedOnly) ParkedOnlyOnClickListener.create { select(vehicle.id) }
                                else OnClickListener { select(vehicle.id) }).build())
                    }
                    if (!stale && vehicles.isEmpty() && !parkedOnly && error == null)
                        addItem(row("Araçsız devam et") { select(null) })
                }.build())
                setActionStrip(ActionStrip.Builder().apply {
                    if (lastPage > 0) addAction(action("Diğer araçlar") { page = (page + 1) % (lastPage + 1); safeInvalidate() })
                    else addAction(action("Vazgeç") { screenManager.pop() })
                }.build())
            }.build()
        return MapWithContentTemplate.Builder().setContentTemplate(list).build()
    }
    private fun select(id: String?) {
        if (busy || loading || stale || !isVisibleScreen()) return
        busy = true; error = null; safeInvalidate()
        scope.launch {
            try {
                val fleet = app.garage.vehicles.first()
                check((id == null && !parkedOnly && fleet.none { it.transport == transport }) ||
                    fleet.any { it.id == id && it.transport == transport }) { "Araç değişti. Yeniden seçin." }
                check(isVisibleScreen() && expected == carVehicleStartSnapshot(navigation.state.value, transport)) {
                    "Yolculuk değişti. Geri dönüp yeniden seçin."
                }
                operation(id, ::isVisibleScreen)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Araç seçimi uygulanamadı." }
            finally { busy = false; safeInvalidate() }
        }
    }
}
