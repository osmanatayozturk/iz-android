package org.iz.navigation.ui.history

import org.junit.Assert.*
import org.junit.Test

class UiHistoryTest {
    @Test fun menuReturnsToActualJourneyDetailInsteadOfInventingMapOrigin() {
        val history = UiHistory()
        history.push(UiPage.JOURNEYS)
        val detail = history.push(UiPage.JOURNEY, "journey-a")
        history.push(UiPage.SETTINGS)
        assertEquals(detail.key, history.current.parentKey)
        assertTrue(history.back()); assertEquals(detail, history.current)
    }
    @Test fun everyDomainFamilyRetainsItsActualCaller() {
        for (origin in listOf(UiPage.STATISTICS, UiPage.COLLECTIONS, UiPage.ROUTES, UiPage.WEATHER,
            UiPage.GROUP, UiPage.COMMUNITY, UiPage.CONTRIBUTIONS, UiPage.MAP_EDITS, UiPage.SETTINGS, UiPage.HEATMAP)) {
            val history = UiHistory(); val parent = history.push(origin, accountKey = "a")
            val child = history.push(if (origin == UiPage.ROUTES) UiPage.GPX else UiPage.JOURNEY, "entity-a")
            history.push(UiPage.PLACE, "place-a")
            history.back(); assertEquals(child, history.current)
            history.back(); assertEquals(parent, history.current)
        }
    }
    @Test fun safeBrowsingStateAndActualOriginSurviveRoundTrip() {
        val history = UiHistory(); val list = history.push(UiPage.JOURNEYS)
        history.updateBrowse(list.key, UiBrowseState(query = "summer", filter = "motorcycle", sort = "oldest", anchorId = "j-8", offset = 23))
        history.push(UiPage.JOURNEY, "j-8"); history.push(UiPage.SETTINGS)
        val restored = UiHistory.restore(history.save())
        assertEquals(history.entries.value, restored.entries.value)
        restored.back(); assertEquals("j-8", restored.current.entityId)
        restored.back(); assertEquals("summer", restored.current.browse.query)
        assertEquals(23, restored.current.browse.offset)
    }
    @Test fun legacyUnknownAndMalformedPayloadsFailClosedToColdMap() {
        for (saved in listOf(null, true, mapOf("tab" to 4), listOf("iz.ui.history.v0", 0),
            listOf("iz.ui.history.v1", 99), listOf("iz.ui.history.v1", 1, 1, listOf(listOf("bad"))))) {
            val restored = UiHistory.restore(saved)
            assertEquals(UiPage.MAP, restored.current.page); assertEquals(1, restored.entries.value.size)
        }
        val valid = UiHistory().apply { push(UiPage.SETTINGS) }.save().toMutableList()
        val rows = (valid[3] as List<*>).map { (it as List<*>).toMutableList() }
        rows.last()[2] = "LEGACY_UNKNOWN_PAGE"; valid[3] = rows
        assertEquals(UiPage.MAP, UiHistory.restore(valid).current.page)
    }
    @Test fun removedEntityAndWrongAccountPruneToNearestValidParent() {
        val history = UiHistory(); val list = history.push(UiPage.JOURNEYS)
        history.push(UiPage.JOURNEY, "deleted"); history.push(UiPage.SETTINGS)
        history.prune { it.entityId != "deleted" }
        assertEquals(list.key, history.current.key)
        history.push(UiPage.COMMUNITY, accountKey = "old-account"); history.push(UiPage.SETTINGS)
        history.prune { it.accountKey == null || it.accountKey == "new-account" }
        assertEquals(list.key, history.current.key)
    }
    @Test fun externalColdEntryHasCanonicalFallbackAndWarmEntryHasActualCaller() {
        val cold = UiHistory(); cold.openExternal(UiPage.JOURNEY, "j", cold = true)
        cold.back(); assertEquals(UiPage.JOURNEYS, cold.current.page)
        val warm = UiHistory(); val origin = warm.push(UiPage.WEATHER)
        warm.openExternal(UiPage.JOURNEY, "j", cold = false)
        warm.back(); assertEquals(origin, warm.current)
    }
    @Test fun successfulStartMapResetCannotBeReplayedByBack() {
        val history = UiHistory(); history.push(UiPage.WEATHER); history.push(UiPage.DIRECTIONS)
        history.toMap(); assertFalse(history.back()); assertEquals(UiPage.MAP, history.current.page)
        assertEquals(1, UiHistory.restore(history.save()).entries.value.size)
    }
    @Test fun guardedNavigationKeepsOrDiscardsBeforeChangingActualOrigin() {
        val history = UiHistory(); val detail = history.push(UiPage.JOURNEY, "j")
        val guard = UiExitGuard(); val navigation = UiHistoryNavigator(history, guard)
        var discards = 0
        guard.update(UiEditState(detail.key, null, 2, 1, true, false)) { discards++ }
        navigation.open(UiPage.SETTINGS); assertEquals(detail, history.current)
        guard.keepEditing(); assertEquals(0, discards)
        navigation.open(UiPage.SETTINGS); guard.discardAndExit()
        assertEquals(UiPage.SETTINGS, history.current.page); assertEquals(detail.key, history.current.parentKey)
        navigation.back(); assertEquals(detail, history.current); assertEquals(1, discards)
    }
    @Test fun repeatedBrowsingIsBoundedAndRestorationCannotInjectCyclesOrDuplicateKeys() {
        val history = UiHistory(); repeat(60) { history.push(UiPage.SETTINGS) }
        assertTrue(history.entries.value.size <= UiHistory.MAX_ENTRIES)
        val restored = UiHistory.restore(history.save()); assertEquals(history.entries.value, restored.entries.value)
        val saved = history.save().toMutableList()
        val rows = (saved[3] as List<*>).map { (it as List<*>).toMutableList() }
        rows.last()[0] = rows.first()[0]; saved[3] = rows
        assertEquals(1, UiHistory.restore(saved).entries.value.size)
    }
}
