package org.iz.navigation.data

/** Canonical place order shared by persistence, migration, backup and the sort editor. */
object PlaceOrderRules {
    const val MAX_ORDER = 1_000_000_000L
    // Match SQLite NOCASE, which folds ASCII rather than using the device locale.
    private fun nameKey(value: String) = value.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
    fun ordered(places: List<Place>): List<Place> = places.sortedWith(compareBy<Place> { it.sortOrder }.thenBy { nameKey(it.name) }.thenBy { it.id })
    fun validate(places: List<Place>) {
        require(places.all { it.sortOrder in 0..MAX_ORDER }) { "Geçersiz yer sırası." }
    }
    fun legacy(places: List<Place>, visits: List<Visit>): List<Place> {
        val latest = visits.groupingBy { it.placeId }.fold(Long.MIN_VALUE) { value, visit -> maxOf(value, visit.visitedAt) }
        val ids = places.sortedWith(compareByDescending<Place> { latest[it.id] ?: 0L }.thenBy { nameKey(it.name) }.thenBy { it.id }).map { it.id }
        val positions = ids.withIndex().associate { it.value to it.index.toLong() }
        return places.map { it.copy(sortOrder = positions.getValue(it.id)) }
    }
    /** Preserve explicit v6 ranks. Equal imported ranks get a stable order before future edits. */
    fun normalizeDuplicates(places: List<Place>): List<Place> {
        validate(places)
        if (places.map { it.sortOrder }.toSet().size == places.size) return places
        val positions = ordered(places).mapIndexed { index, place -> place.id to index.toLong() }.toMap()
        return places.map { it.copy(sortOrder = positions.getValue(it.id)) }
    }
    fun reorder(current: List<Place>, originalOrder: List<String>, newOrder: List<String>): List<Place> {
        val ids = ordered(current).map { it.id }
        check(ids == originalOrder) { "Yer listesi değişti. İptal edip sıralamayı yeniden aç." }
        require(newOrder.size == ids.size && newOrder.toSet().size == newOrder.size && newOrder.toSet() == ids.toSet()) {
            "Sıralama bütün yerleri birer kez içermeli."
        }
        val byId = current.associateBy { it.id }
        return newOrder.mapIndexed { index, id -> byId.getValue(id).copy(sortOrder = index.toLong()) }
    }
}
