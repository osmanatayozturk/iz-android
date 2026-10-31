package org.iz.navigation.data

/** A confirmation can delete only its exact, still-editable local snapshot. */
internal fun mapEditDeletionMatches(expected: MapEditDraft, current: MapEditDraft?): Boolean =
    current != null && current == expected &&
        (current.status == MapEditStatus.DRAFT || current.status == MapEditStatus.FAILED)
