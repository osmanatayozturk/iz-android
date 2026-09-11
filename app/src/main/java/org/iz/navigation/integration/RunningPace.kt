package org.iz.navigation.integration

/** Elapsed-time pace includes stops and GPS gaps; missing distance never becomes a pace. */
fun averagePaceMinutesPerKm(distanceMeters: Double, elapsedMillis: Long): Double? {
    if (!distanceMeters.isFinite() || distanceMeters <= 0.0 || elapsedMillis <= 0) return null
    return (elapsedMillis.toDouble() / 60.0 / distanceMeters).takeIf { it.isFinite() && it > 0.0 }
}
