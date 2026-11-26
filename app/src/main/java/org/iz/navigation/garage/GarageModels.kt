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

/** Vehicles that were unique for their transport when this temporary recording began. Local to this journey. */
@Entity(tableName = "journey_start_vehicles", primaryKeys = ["journeyId", "transport"], foreignKeys = [
    ForeignKey(entity = Journey::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = GarageVehicle::class, parentColumns = ["id"], childColumns = ["vehicleId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("vehicleId")])
internal data class JourneyStartVehicle(val journeyId: String, val transport: Transport, val vehicleId: String)

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
    private const val MIN_INTERVALS = 4

    private data class Interval(val distance: Double, val litres: Double, val cost: Double) {
        val rate: Double get() = litres / distance
    }

    private data class PartialPurchase(val at: Long, val distanceSincePurchase: Double, val distanceSinceFull: Double,
        val fuelBeforeTravel: Double) {
        fun reserveAt(rate: Double, capacity: Double): Double? =
            (fuelBeforeTravel - distanceSinceFull * rate).takeIf { it in 0.0..capacity }
    }

    private class Tank(private val full: FuelFill, private val capacity: Double, private val rateAtStart: Double?) {
        var lastPurchase = full
            private set
        private var partialLitres = 0.0
        private var partialCost = 0.0
        private var minimumRate = 0.0
        private var maximumRate = Double.POSITIVE_INFINITY
        private val pendingPartials = mutableListOf<PartialPurchase>()

        fun addPartial(fill: FuelFill, observe: (distance: Double, reserve: Double) -> Unit): Boolean {
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
            val partial = PartialPurchase(fill.at, fill.odometerKm - lastPurchase.odometerKm, distance, litresBefore)
            val reserve = rateAtStart?.takeIf { pendingPartials.isEmpty() && supportsRate(it) }
                ?.let { partial.reserveAt(it, capacity) }
            if (reserve != null) observe(partial.distanceSincePurchase, reserve) else pendingPartials += partial
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
            val litresPerHundredKm = litres * 100 / distance
            if (distance <= 0 || !supportsRate(litres / distance) || !litresPerHundredKm.isFinite() || !cost.isFinite()) return null
            return Interval(distance, litres, cost)
        }

        fun forEachVerifiedDeferredPurchase(interval: Interval, action: (at: Long, distance: Double, reserve: Double) -> Unit) {
            for (partial in pendingPartials) {
                val reserve = partial.reserveAt(interval.rate, capacity) ?: continue
                action(partial.at, partial.distanceSincePurchase, reserve)
            }
        }

        fun projection(rate: Double): TankProjection? {
            if (pendingPartials.isNotEmpty() || !supportsRate(rate)) return null
            val totalRange = ((capacity + partialLitres) / rate).takeIf { it.isFinite() } ?: return null
            return TankProjection(full.odometerKm, lastPurchase.odometerKm, totalRange)
        }
    }

    internal data class TankProjection(val fullOdometerKm: Double, val lastPurchaseKm: Double, val totalRangeKm: Double) {
        fun rangeAt(odometerKm: Double, fullRangeKm: Double): Double? {
            if (!odometerKm.isFinite() || odometerKm < lastPurchaseKm) return null
            return (totalRangeKm - (odometerKm - fullOdometerKm)).takeIf { it in 0.0..fullRangeKm }
        }
    }

    internal class Prepared(
        val learningFullCount: Int,
        private val litresPer100Km: Double?,
        private val fullRangeKm: Double?,
        private val typicalIntervalKm: Double?,
        private val typicalRefuelRangeKm: Double?,
        private val tlPerKm: Double?,
        private val tank: TankProjection?,
    ) {
        fun statsAt(odometerKm: Double): FuelStats {
            val remaining = fullRangeKm?.let { tank?.rangeAt(odometerKm, it) }
            val untilTypical = if (typicalIntervalKm != null && remaining != null && tank != null) {
                typicalIntervalKm - (odometerKm - tank.lastPurchaseKm)
            } else null
            return FuelStats(odometerKm, learningFullCount, litresPer100Km, fullRangeKm, typicalIntervalKm,
                tlPerKm, remaining, untilTypical, typicalRefuelRangeKm)
        }
    }

    private fun FuelFill.isReliable(capacity: Double): Boolean = id.isNotBlank() && at >= 0 &&
        odometerKm.isFinite() && odometerKm >= 0 && litres.isFinite() && litres > 0 && litres <= capacity &&
        pricePerLitre.isFinite() && pricePerLitre > 0 && totalTl.isFinite()

    fun calculate(vehicle: GarageVehicle, fills: List<FuelFill>, odometer: Double): FuelStats =
        prepare(vehicle, fills).statsAt(odometer)

    fun prepare(vehicle: GarageVehicle, fills: List<FuelFill>): Prepared {
        val capacity = vehicle.capacityLitres
        val ordered = fills.filter { it.vehicleId == vehicle.id }.sortedBy { it.at }
        val consumption = FuelMetricLearner(0.01)
        val purchaseDistance = FuelMetricLearner(0.1)
        val purchaseReserve = FuelMetricLearner(0.01)
        var tank: Tank? = null
        var lastKnownOdometer: Double? = null
        var haveFull = false
        var measuredIntervals = 0
        var measuredKm = 0.0
        var measuredCost = 0.0
        for ((index, fill) in ordered.withIndex()) {
            val duplicateTime = fill.at == ordered.getOrNull(index - 1)?.at || fill.at == ordered.getOrNull(index + 1)?.at
            if (!capacity.isFinite() || duplicateTime || !fill.isReliable(capacity) ||
                lastKnownOdometer?.let { fill.odometerKm <= it } == true) {
                tank = null
                continue
            }
            lastKnownOdometer = fill.odometerKm
            if (fill.missedBefore) tank = null
            val canScore = measuredIntervals >= MIN_INTERVALS
            if (!fill.full) {
                val accepted = tank?.addPartial(fill) { distance, reserve ->
                    purchaseDistance.observe(fill.at, distance, score = canScore)
                    purchaseReserve.observe(fill.at, reserve, score = false)
                }
                if (accepted == false) tank = null
                continue
            }
            val previousTank = tank
            val interval = previousTank?.close(fill)
            // A full purchase measures its pre-fill reserve even when no preceding distance is known.
            val reserve = capacity - fill.litres
            if (canScore) purchaseReserve.scoreOutcome(reserve)
            if (previousTank != null && interval != null) {
                val distance = fill.odometerKm - previousTank.lastPurchase.odometerKm
                if (canScore && purchaseDistance.estimate?.isFinite() == true) {
                    // No purchase-distance inputs were added while this suffix was pending.
                    previousTank.forEachVerifiedDeferredPurchase(interval) { _, partialDistance, _ ->
                        purchaseDistance.scoreOutcome(partialDistance)
                    }
                    purchaseDistance.scoreOutcome(distance)
                }
                // Add the newly verified partials only after the forecasts have been scored.
                previousTank.forEachVerifiedDeferredPurchase(interval) { at, partialDistance, partialReserve ->
                    purchaseDistance.observe(at, partialDistance, score = false)
                    purchaseReserve.observe(at, partialReserve, score = false)
                }
                consumption.observe(fill.at, interval.litres * 100, interval.distance, score = canScore)
                purchaseDistance.observe(fill.at, distance, score = false)
                measuredIntervals++
                measuredKm += interval.distance
                measuredCost += interval.cost
            }
            purchaseReserve.observe(fill.at, reserve, score = false)
            tank = Tank(fill, capacity, consumption.estimate?.div(100))
            haveFull = true
        }
        val learned = measuredIntervals >= MIN_INTERVALS
        val litresPer100Km = consumption.estimate?.takeIf { learned && it.isFinite() && it > 0 }
        val rate = litresPer100Km?.div(100)?.takeIf { it > 0 }
        val range = rate?.let { (capacity / it).takeIf { result -> result.isFinite() && result > 0 } }
        val cost = if (measuredKm.isFinite() && measuredKm > 0) (measuredCost / measuredKm).takeIf { it.isFinite() } else null
        return Prepared(
            learningFullCount = if (haveFull) minOf(5, measuredIntervals + 1) else 0,
            litresPer100Km = litresPer100Km,
            fullRangeKm = range,
            typicalIntervalKm = purchaseDistance.estimate?.takeIf { learned && it.isFinite() },
            typicalRefuelRangeKm = rate?.let { divisor -> purchaseReserve.estimate?.div(divisor)?.takeIf { it.isFinite() } },
            tlPerKm = cost,
            tank = rate?.let { tank?.projection(it) },
        )
    }
}
