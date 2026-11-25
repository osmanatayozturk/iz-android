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
    val typicalRefuelRangeKm: Double? = null,
    val distanceBaselineAt: Long = 0L,
)

internal fun FuelStats.needsFuel(extraKm: Double): Boolean {
    if (learningFullCount < 5 || !extraKm.isFinite() || extraKm < 0 || odometerKm < 0 ||
        !(odometerKm + extraKm).isFinite()) return false
    val full = fullRangeKm?.takeIf { it.isFinite() && it > 0 } ?: return false
    val remaining = remainingRangeKm?.takeIf { it in 0.0..full } ?: return false
    if (extraKm > remaining) return false
    val distanceToTypical = untilTypicalKm?.takeIf { it.isFinite() } ?: return false
    val reserve = typicalRefuelRangeKm?.takeIf { it in 0.0..full } ?: return false
    // The reserve's conversion from litres can round an equal distance one step down.
    return extraKm >= distanceToTypical || remaining - extraKm <= Math.nextUp(reserve)
}

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
    private const val MAX_INTERVALS = 5
    private const val MIN_INTERVALS = 4

    private data class Interval(val distance: Double, val litres: Double, val cost: Double)

    private data class PartialPurchase(val distanceSincePurchase: Double, val distanceSinceFull: Double,
        val fuelBeforeTravel: Double) {
        fun reserveAt(rate: Double, capacity: Double): Double? =
            (fuelBeforeTravel - distanceSinceFull * rate).takeIf { it in 0.0..capacity }
    }

    private fun <T> ArrayDeque<T>.keepRecent(value: T) {
        if (size == MAX_INTERVALS) removeFirst()
        addLast(value)
    }

    private class Tank(private val full: FuelFill, private val capacity: Double, private val rateAtStart: Double?) {
        var lastPurchase = full
            private set
        private var partialLitres = 0.0
        private var partialCost = 0.0
        private var minimumRate = 0.0
        private var maximumRate = Double.POSITIVE_INFINITY
        private val recentPartials = ArrayDeque<PartialPurchase>(MAX_INTERVALS)
        private val verifiedPartials = ArrayDeque<PartialPurchase>(MAX_INTERVALS)

        fun addPartial(fill: FuelFill): Boolean {
            val distance = fill.odometerKm - full.odometerKm
            val litresBefore = capacity + partialLitres
            val totalPartial = partialLitres + fill.litres
            val cost = partialCost + fill.totalTl
            if (distance <= 0 || !litresBefore.isFinite() || !totalPartial.isFinite() || !cost.isFinite()) return false
            // The tank must hold at least zero before this purchase and at most capacity afterward.
            val lower = maxOf(minimumRate, totalPartial / distance)
            val upper = minOf(maximumRate, litresBefore / distance)
            if (!lower.isFinite() || lower > Math.nextUp(upper)) return false
            minimumRate = lower
            maximumRate = upper
            val partial = PartialPurchase(fill.odometerKm - lastPurchase.odometerKm, distance, litresBefore)
            recentPartials.keepRecent(partial)
            if (rateAtStart != null && supportsRate(rateAtStart) && partial.reserveAt(rateAtStart, capacity) != null) {
                verifiedPartials.keepRecent(partial)
            }
            partialLitres = totalPartial
            partialCost = cost
            lastPurchase = fill
            return true
        }

        private fun supportsRate(rate: Double): Boolean = rate.isFinite() && rate > 0 &&
            rate >= Math.nextDown(minimumRate) && rate <= Math.nextUp(maximumRate)

        fun close(fill: FuelFill): Interval? {
            val distance = fill.odometerKm - full.odometerKm
            val litres = partialLitres + fill.litres
            val cost = partialCost + fill.totalTl
            if (distance <= 0 || !supportsRate(litres / distance) || !cost.isFinite()) return null
            return Interval(distance, litres, cost)
        }

        fun addPartialObservations(distances: ArrayDeque<Double>, reserves: ArrayDeque<Double>, interval: Interval? = null) {
            // With no measured closing interval, keep only the purchases verified at the time they happened.
            val rate = interval?.let { it.litres / it.distance } ?: rateAtStart ?: return
            val purchases = if (interval == null) verifiedPartials else recentPartials
            for (partial in purchases) {
                val reserve = partial.reserveAt(rate, capacity) ?: continue
                distances.keepRecent(partial.distanceSincePurchase)
                reserves.keepRecent(reserve)
            }
        }

        fun rangeAt(odometer: Double, rate: Double, fullRange: Double): Double? {
            if (!odometer.isFinite() || odometer < lastPurchase.odometerKm || !supportsRate(rate)) return null
            val range = (capacity + partialLitres) / rate - (odometer - full.odometerKm)
            return range.takeIf { it in 0.0..fullRange }
        }
    }

    private fun FuelFill.isReliable(capacity: Double): Boolean = id.isNotBlank() && at >= 0 &&
        odometerKm.isFinite() && odometerKm >= 0 && litres.isFinite() && litres > 0 && litres <= capacity &&
        pricePerLitre.isFinite() && pricePerLitre > 0 && totalTl.isFinite()

    private fun Collection<Interval>.learnedRate(): Double? {
        if (size < MIN_INTERVALS) return null
        val km = sumOf { it.distance }
        if (!km.isFinite() || km <= 0) return null
        return (sumOf { it.litres } / km).takeIf { it.isFinite() && it > 0 && (it * 100).isFinite() }
    }

    fun calculate(vehicle: GarageVehicle, fills: List<FuelFill>, odometer: Double): FuelStats {
        val capacity = vehicle.capacityLitres
        val ordered = fills.filter { it.vehicleId == vehicle.id }.sortedBy { it.at }
        val recent = ArrayDeque<Interval>(MAX_INTERVALS)
        val purchaseDistances = ArrayDeque<Double>(MAX_INTERVALS)
        val purchaseReserves = ArrayDeque<Double>(MAX_INTERVALS)
        var tank: Tank? = null
        var rate: Double? = null
        var lastKnownOdometer: Double? = null
        var haveFull = false
        fun endUnmeasuredInterval() {
            tank?.addPartialObservations(purchaseDistances, purchaseReserves)
            tank = null
        }
        for ((index, fill) in ordered.withIndex()) {
            val duplicateTime = fill.at == ordered.getOrNull(index - 1)?.at || fill.at == ordered.getOrNull(index + 1)?.at
            if (!capacity.isFinite() || duplicateTime || !fill.isReliable(capacity) ||
                lastKnownOdometer?.let { fill.odometerKm <= it } == true) {
                endUnmeasuredInterval()
                continue
            }
            lastKnownOdometer = fill.odometerKm
            if (fill.missedBefore) endUnmeasuredInterval()
            if (!fill.full) {
                if (tank?.addPartial(fill) == false) endUnmeasuredInterval()
                continue
            }
            val previousTank = tank
            if (previousTank != null) {
                val interval = previousTank.close(fill)
                previousTank.addPartialObservations(purchaseDistances, purchaseReserves, interval)
                if (interval != null) {
                    recent.keepRecent(interval)
                    rate = recent.learnedRate()
                    purchaseDistances.keepRecent(fill.odometerKm - previousTank.lastPurchase.odometerKm)
                }
            }
            // A full purchase measures its pre-fill reserve even when no preceding distance is known.
            purchaseReserves.keepRecent(capacity - fill.litres)
            tank = Tank(fill, capacity, rate)
            haveFull = true
        }
        val km = recent.sumOf { it.distance }
        val currentTank = tank
        currentTank?.addPartialObservations(purchaseDistances, purchaseReserves)
        val range = rate?.let { (capacity / it).takeIf { result -> result.isFinite() && result > 0 } }
        val typical = rate?.let { purchaseDistances.average().takeIf { distance -> distance.isFinite() } }
        val reserve = rate?.let { (purchaseReserves.average() / it).takeIf { result -> result.isFinite() } }
        val remaining = if (rate != null && range != null) currentTank?.rangeAt(odometer, rate, range) else null
        val untilTypical = if (typical != null && remaining != null && currentTank != null) {
            typical - (odometer - currentTank.lastPurchase.odometerKm)
        } else null
        return FuelStats(
            odometerKm = odometer,
            learningFullCount = if (haveFull) minOf(5, recent.size + 1) else 0,
            litresPer100Km = rate?.times(100),
            fullRangeKm = range,
            typicalIntervalKm = typical,
            tlPerKm = if (km.isFinite() && km > 0) (recent.sumOf { it.cost } / km).takeIf { it.isFinite() } else null,
            remainingRangeKm = remaining,
            untilTypicalKm = untilTypical,
            typicalRefuelRangeKm = reserve,
        )
    }
}
