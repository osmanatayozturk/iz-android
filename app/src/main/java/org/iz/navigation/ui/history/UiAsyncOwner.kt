package org.iz.navigation.ui.history

/** OS result lease. Transient only: never serialized, restored, or used to replay a launch. */
data class UiAsyncOwner private constructor(val entryKey: String, val editorKey: String?,
    val editRevision: Long?, val accountKey: String?, private val registration: Any?) {
    fun matches(currentEntry: String, edit: UiEditState?, currentRegistration: Any? = null) = entryKey == currentEntry &&
        editorKey == edit?.entryKey && editRevision == edit?.editRevision && accountKey == edit?.accountKey && registration === currentRegistration
    companion object {
        fun capture(entry: String, edit: UiEditState?, registration: Any? = null) = UiAsyncOwner(entry, edit?.entryKey, edit?.editRevision, edit?.accountKey, registration)
    }
}
