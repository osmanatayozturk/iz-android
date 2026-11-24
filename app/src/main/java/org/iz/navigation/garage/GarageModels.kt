package org.iz.navigation.garage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import java.util.UUID

@Entity(tableName = "garage_vehicles")
data class GarageVehicle(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val transport: Transport,
    val capacityLitres: Double,
    val typicalThresholdKm: Double = 20.0,
    val rangeThresholdKm: Double = 40.0,
    val odometerKm: Double = 0.0,
    val odometerAt: Long = if (odometerKm == 0.0) 0L else System.currentTimeMillis(),
)

@Entity(tableName = "fuel_fills", foreignKeys = [ForeignKey(entity = GarageVehicle::class,
    parentColumns = ["id"], childColumns = ["vehicleId"], onDelete = ForeignKey.CASCADE)], indices = [Index("vehicleId")])
data class FuelFill(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val vehicleId: String,
    val at: Long,
    val odometerKm: Double,
    val litres: Double,
    val pricePerLitre: Double,
    val full: Boolean = true,
    val missedBefore: Boolean = false,
) {
    val totalTl: Double get() = litres * pricePerLitre
}

@Entity(tableName = "journey_vehicles", foreignKeys = [
    ForeignKey(entity = GarageVehicle::class, parentColumns = ["id"], childColumns = ["vehicleId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Journey::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("vehicleId")])
data class JourneyVehicle(@PrimaryKey val journeyId: String, val vehicleId: String)

data class FuelStats(
    val odometerKm: Double,
    val learningFullCount: Int,
    val litresPer100Km: Double?,
    val fullRangeKm: Double?,
    val typicalIntervalKm: Double?,
    val tlPerKm: Double?,
    val remainingRangeKm: Double?,
    val untilTypicalKm: Double?,
)

internal fun FuelStats.needsFuel(vehicle: GarageVehicle, extraKm: Double): Boolean =
    untilTypicalKm?.let { it - extraKm <= vehicle.typicalThresholdKm } == true ||
        remainingRangeKm?.let { it - extraKm <= vehicle.rangeThresholdKm } == true

internal fun GarageVehicle.validate() {
    require(id.isNotBlank() && name.isNotBlank() && name.length <= 160 &&
        transport in setOf(Transport.CAR, Transport.MOTORCYCLE)) { "Araç adı ve türünü kontrol et." }
    require(capacityLitres.isFinite() && capacityLitres > 0 && odometerAt >= 0 &&
        listOf(typicalThresholdKm, rangeThresholdKm, odometerKm).all { it.isFinite() && it >= 0 }) {
        "Depo kapasitesi, sayaç veya eşik geçersiz."
    }
}

internal fun FuelFill.validate() {
    require(id.isNotBlank() && vehicleId.isNotBlank() && at >= 0 && odometerKm.isFinite() && odometerKm >= 0 &&
        litres.isFinite() && litres > 0 && pricePerLitre.isFinite() && pricePerLitre > 0 && totalTl.isFinite()) {
        "Dolum bilgilerini kontrol et."
    }
}

internal fun validateFillOrder(ordered: List<FuelFill>) {
    require(ordered.zipWithNext().all { (a, b) -> b.at > a.at && b.odometerKm >= a.odometerKm }) {
        "Dolum tarihi ve kilometresi önceki ve sonraki dolumlarla uyumlu olmalı."
    }
}

internal object FuelHistory {
    private data class Interval(val distance: Double, val litres: Double, val cost: Double, val partial: Boolean)

    fun calculate(vehicle: GarageVehicle, fills: List<FuelFill>, odometer: Double): FuelStats {
        var start: FuelFill? = null
        var litres = 0.0
        var cost = 0.0
        var partial = false
        var streak = 0
        var learned = false
        val intervals = mutableListOf<Interval>()
        for (fill in fills.sortedBy { it.at }) {
            if (fill.missedBefore) {
                start = null
                streak = 0
                litres = 0.0
                cost = 0.0
                partial = false
            }
            litres += fill.litres
            cost += fill.totalTl
            if (!fill.full) {
                partial = true
                continue
            }
            val km = start?.let { fill.odometerKm - it.odometerKm }
            if (km != null && km > 0) {
                intervals += Interval(km, litres, cost, partial)
                streak++
            } else streak = 1
            if (streak >= 5) learned = true
            start = fill
            litres = 0.0
            cost = 0.0
            partial = false
        }
        val recent = intervals.takeLast(5)
        val km = recent.sumOf { it.distance }
        val rate = if (learned && km > 0) recent.sumOf { it.litres } / km else null
        val clean = intervals.filterNot { it.partial }.map { it.distance }.sorted()
        val typical = if (learned && clean.isNotEmpty())
            (clean[(clean.size - 1) / 2] + clean[clean.size / 2]) / 2 else null
        val range = rate?.let { vehicle.capacityLitres / it }
        val travelled = start?.let { (odometer - it.odometerKm).coerceAtLeast(0.0) }
        val remaining = if (travelled != null && rate != null && range != null)
            (range + litres / rate - travelled).coerceIn(0.0, range) else null
        return FuelStats(odometer, if (learned) 5 else streak, rate?.times(100), range, typical,
            if (km > 0) recent.sumOf { it.cost } / km else null, remaining,
            if (typical != null && travelled != null && !partial) typical - travelled else null)
    }
}
