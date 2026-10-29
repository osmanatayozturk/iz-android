package org.iz.navigation.ui.history

/** Ephemeral feature-local equality/revision tracker. No working values enter the saved UI history. */
class UiWorkingRevision {
    data class Progress(val revision: Long, val acknowledged: Long, val dirty: Boolean)
    private val unset = Any()
    private var last: Any? = unset
    private var revision = 0L
    private var acknowledged = 0L
    fun observe(working: Any?, baseline: Any?): Progress {
        if (last === unset) { if (working != baseline) revision++ }
        else if (working != last) revision++
        last = working
        if (working == baseline) acknowledged = revision
        return Progress(revision, acknowledged, working != baseline)
    }
}
