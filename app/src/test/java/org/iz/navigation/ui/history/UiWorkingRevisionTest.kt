package org.iz.navigation.ui.history

import org.junit.Assert.*
import org.junit.Test

class UiWorkingRevisionTest {
    @Test fun openingUnchangedFormIsCleanAndOnlyRealWorkingDeltaAdvancesRevision() {
        val revisions = UiWorkingRevision()
        val clean = revisions.observe(listOf("title", "note"), listOf("title", "note"))
        assertFalse(clean.dirty)
        val dirty = revisions.observe(listOf("new", "note"), listOf("title", "note"))
        assertTrue(dirty.dirty); assertTrue(dirty.revision > dirty.acknowledged)
        assertEquals(dirty, revisions.observe(listOf("new", "note"), listOf("title", "note")))
    }
    @Test fun acknowledgedBaselineOrRevertingWorkingDeltaMakesFormCleanWithoutSaving() {
        val revisions = UiWorkingRevision()
        revisions.observe("old", "old"); revisions.observe("new", "old")
        val acknowledged = revisions.observe("new", "new")
        assertFalse(acknowledged.dirty); assertEquals(acknowledged.revision, acknowledged.acknowledged)
        revisions.observe("temporary", "new")
        val reverted = revisions.observe("new", "new")
        assertFalse(reverted.dirty)
    }
}
