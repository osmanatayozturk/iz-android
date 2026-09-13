package org.iz.navigation.data

import java.util.UUID
import org.iz.navigation.weather.RoutePreferences
import org.iz.navigation.weather.RouteStop

/** An editable plan, not a persisted routing provider response. */
data class SavedRoutePlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val stops: List<RouteStop>,
    val transport: Transport,
    val preferences: RoutePreferences = RoutePreferences.defaults(transport),
    val travelSpeedKmh: Double? = null,
    val originUsesCurrentLocation: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

data class JourneyCollection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

data class CollectionMembership(
    val collectionId: String,
    val journeyId: String,
    val sortOrder: Long,
)

