package org.iz.navigation.navigation

import org.iz.navigation.weather.*

data class RouteEditOwner(val sessionId: String, val routeId: String, val routeRevision: Long) {
    fun matches(state: NavigationState) = state.sessionId == sessionId && state.route?.id == routeId && state.routeRevision == routeRevision
    companion object { fun from(state: NavigationState): RouteEditOwner? =
        state.sessionId?.let { session -> state.route?.let { RouteEditOwner(session, it.id, state.routeRevision) } } }
}
data class RouteEditPreview(val id: String, val owner: RouteEditOwner, val action: RouteEditAction,
    val request: RouteModificationRequest, val route: PlannedRoute)
data class RouteEditState(val generation: Long = 0, val loading: Boolean = false, val requestId: String? = null,
    val preview: RouteEditPreview? = null, val error: String? = null)
