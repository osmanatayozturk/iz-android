package org.iz.navigation.weather

/** User plans and effective routes differ only by an explicitly calculated approach. */
object RouteStopLimits {
    const val MAX_PLANNED_STOPS = 20
    const val MAX_EFFECTIVE_STOPS = 21

    fun requirePlanned(count: Int) {
        require(count in 2..MAX_PLANNED_STOPS) { "Rota başlangıç ve varış dahil en fazla 20 durak içerebilir." }
    }

    fun requireEffective(count: Int) {
        require(count in 2..MAX_EFFECTIVE_STOPS) { "Yaklaşım dahil rota en fazla 21 durak içerebilir." }
    }
}
