package org.iz.navigation.ui.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class UiPage { MAP, JOURNEYS, JOURNEY, PLACES, PLACE, STATISTICS, COLLECTIONS, ROUTES,
    DIRECTIONS, GPX, WEATHER, GROUP, COMMUNITY, CONTRIBUTIONS, MAP_EDITS, OSM_ACTIONS, SETTINGS, HEATMAP }

data class UiBrowseState(val query: String = "", val filter: String = "", val sort: String = "",
    val anchorId: String = "", val offset: Int = 0)
data class UiEntry(val key: String, val parentKey: String?, val page: UiPage,
    val entityId: String? = null, val accountKey: String? = null, val browse: UiBrowseState = UiBrowseState())

/** Structural UI data only. No domain graph, draft, auth/permission/result token, callback or command is saved. */
class UiHistory private constructor(initial: List<UiEntry>, private var next: Long,
    private val restoredKeys: Set<String> = emptySet()) {
    constructor() : this(listOf(UiEntry("map", null, UiPage.MAP)), 1L)
    private val mutableEntries = MutableStateFlow(initial)
    val entries: StateFlow<List<UiEntry>> = mutableEntries.asStateFlow()
    val current: UiEntry get() = mutableEntries.value.last()
    fun isRestoredEntry(key: String) = key in restoredKeys
    fun push(page: UiPage, entityId: String? = null, accountKey: String? = null): UiEntry {
        require(entityId == null || entityId.length <= ID_LIMIT)
        require(accountKey == null || accountKey.length <= ID_LIMIT)
        val entry = UiEntry("entry-${next++}", current.key, page, entityId, accountKey)
        val all = mutableEntries.value + entry
        val bounded = if (all.size <= MAX_ENTRIES) all else listOf(all.first()) + all.takeLast(MAX_ENTRIES - 1)
        mutableEntries.value = bounded.mapIndexed { index, row -> row.copy(parentKey = bounded.getOrNull(index - 1)?.key) }
        return current
    }
    fun back(): Boolean {
        if (mutableEntries.value.size == 1) return false
        mutableEntries.value = mutableEntries.value.dropLast(1); return true
    }
    fun toMap() { mutableEntries.value = listOf(mutableEntries.value.first()) }
    fun updateBrowse(key: String, value: UiBrowseState) {
        val safe = value.copy(query = value.query.take(TEXT_LIMIT), filter = value.filter.take(ID_LIMIT),
            sort = value.sort.take(ID_LIMIT), anchorId = value.anchorId.take(ID_LIMIT), offset = value.offset.coerceIn(0, 100_000))
        mutableEntries.value = mutableEntries.value.map { if (it.key == key) it.copy(browse = safe) else it }
    }
    fun prune(valid: (UiEntry) -> Boolean) {
        val firstInvalid = mutableEntries.value.indexOfFirst { it.page != UiPage.MAP && !valid(it) }
        if (firstInvalid >= 0) mutableEntries.value = mutableEntries.value.take(firstInvalid.coerceAtLeast(1))
    }
    fun openExternal(page: UiPage, entityId: String? = null, accountKey: String? = null, cold: Boolean) {
        if (cold) {
            toMap()
            when (page) {
                UiPage.JOURNEY -> push(UiPage.JOURNEYS)
                UiPage.PLACE -> push(UiPage.PLACES)
                else -> Unit
            }
        }
        push(page, entityId, accountKey)
    }
    fun save(): List<Any> = listOf(NAMESPACE, VERSION, next, mutableEntries.value.map { row ->
        listOf(row.key, row.parentKey.orEmpty(), row.page.name, row.entityId.orEmpty(), row.accountKey.orEmpty(),
            row.browse.query, row.browse.filter, row.browse.sort, row.browse.anchorId, row.browse.offset)
    })
    companion object {
        const val NAMESPACE = "iz.ui.history.v1"
        const val MAX_ENTRIES = 24
        private const val VERSION = 1
        private const val ID_LIMIT = 160
        private const val TEXT_LIMIT = 256
        /** Unknown/legacy/malformed shape is a cold map, never a command to reconstruct old activity work. */
        fun restore(payload: Any?): UiHistory = runCatching {
            val data = payload as? List<*> ?: error("Unknown UI state")
            require(data.size == 4 && data[0] == NAMESPACE && data[1] == VERSION)
            val next = data[2] as? Long ?: error("Invalid UI sequence")
            require(next in 1 until Long.MAX_VALUE)
            val rows = data[3] as? List<*> ?: error("Invalid entries")
            require(rows.size in 1..MAX_ENTRIES)
            val entries = rows.map { raw ->
                val row = raw as? List<*> ?: error("Invalid entry")
                require(row.size == 10)
                val strings = row.take(9).mapIndexed { index, value ->
                    (value as? String ?: error("Invalid entry field")).also {
                        require(it.length <= if (index == 5) TEXT_LIMIT else ID_LIMIT)
                    }
                }
                val offset = row[9] as? Int ?: error("Invalid browse offset")
                require(offset in 0..100_000)
                UiEntry(strings[0], strings[1].ifEmpty { null }, UiPage.valueOf(strings[2]),
                    strings[3].ifEmpty { null }, strings[4].ifEmpty { null },
                    UiBrowseState(strings[5], strings[6], strings[7], strings[8], offset))
            }
            require(entries.first() == UiEntry("map", null, UiPage.MAP, browse = entries.first().browse))
            require(entries.map { it.key }.toSet().size == entries.size)
            entries.forEachIndexed { index, entry ->
                require(entry.parentKey == entries.getOrNull(index - 1)?.key)
                if (index > 0) require(entry.key.removePrefix("entry-").toLongOrNull()?.let { it in 1 until next } == true)
            }
            UiHistory(entries, next, entries.map { it.key }.toSet())
        }.getOrElse { UiHistory() }
    }
}

/** UI transitions consult the current feature owner; history itself never invokes backend work. */
class UiHistoryNavigator(private val history: UiHistory, private val guard: UiExitGuard) {
    fun open(page: UiPage, entityId: String? = null, accountKey: String? = null) {
        guard.requestExit { guard.update(null); history.push(page, entityId, accountKey) }
    }
    fun back() { guard.requestExit { guard.update(null); history.back() } }
    fun toMap() { guard.requestExit { guard.update(null); history.toMap() } }
    fun pageFlag(page: UiPage): ReadWriteProperty<Any?, Boolean> = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = history.current.page == page
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            if (value && history.current.page != page) open(page)
            else if (!value && history.current.page == page) back()
        }
    }
    fun selected(page: UiPage): ReadWriteProperty<Any?, String?> = object : ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = history.current.takeIf { it.page == page }?.entityId
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
            if (value == null) { if (history.current.page == page) back() }
            else if (history.current.page != page || history.current.entityId != value) open(page, value)
        }
    }
    fun tab(): ReadWriteProperty<Any?, Int> = object : ReadWriteProperty<Any?, Int> {
        private val pages = listOf(UiPage.MAP, UiPage.JOURNEYS, UiPage.PLACES, UiPage.HEATMAP, UiPage.SETTINGS, UiPage.STATISTICS)
        override fun getValue(thisRef: Any?, property: KProperty<*>) = pages.indexOf(history.current.page).coerceAtLeast(0)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { open(pages.getOrElse(value) { UiPage.MAP }) }
    }
}
