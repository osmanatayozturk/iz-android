package org.iz.navigation.health

data class HealthReadWindow(val startAt: Long, val endAt: Long)

fun healthReadWindow(startAt: Long, endAt: Long?, historyStartAt: Long, now: Long): HealthReadWindow? {
    val start = maxOf(startAt, historyStartAt)
    val end = minOf(endAt ?: now, now)
    return if (end > start) HealthReadWindow(start, end) else null
}

data class HealthPage<T>(val records: List<T>, val nextToken: String?)
data class HealthSourceChange<T>(val sourceId: String, val samples: List<T>)
data class HealthChangePage<T>(
    val changes: List<HealthSourceChange<T>>, val nextToken: String,
    val hasMore: Boolean, val expired: Boolean = false,
)
data class CollectedHealthChanges<T>(
    val samples: List<T>, val changedSourceIds: Set<String>, val nextToken: String,
)
class HealthTokenExpired : Exception("Sağlık eşitleme işaretçisi yenilenmeli.")

/** Nothing is returned until all pages succeed; callers can commit one complete snapshot. */
suspend fun <T> collectHealthPages(read: suspend (String?) -> HealthPage<T>): List<T> {
    val records = mutableListOf<T>()
    val visited = mutableSetOf<String>()
    var token: String? = null
    do {
        val page = read(token)
        records += page.records
        token = page.nextToken?.takeIf { it.isNotEmpty() }
        if (token != null) check(visited.add(token) && visited.size <= 10_000) { "Sağlık sayfalaması tamamlanamadı." }
    } while (token != null)
    return records
}

/** Collapse parent-record changes in order, including upserts whose eligible sample list is empty. */
suspend fun <T> collectHealthChanges(token: String, read: suspend (String) -> HealthChangePage<T>): CollectedHealthChanges<T> {
    val latest = linkedMapOf<String, List<T>>()
    val visited = mutableSetOf(token)
    var next = token
    do {
        val page = read(next)
        if (page.expired) throw HealthTokenExpired()
        page.changes.forEach { latest[it.sourceId] = it.samples }
        next = page.nextToken
        if (page.hasMore) check(next.isNotEmpty() && visited.add(next) && visited.size <= 10_000) { "Sağlık değişiklikleri tamamlanamadı." }
    } while (page.hasMore)
    return CollectedHealthChanges(latest.values.flatten(), latest.keys, next)
}
