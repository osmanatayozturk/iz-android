package com.atay.iz.integration.osm

import java.net.URI

data class OsmUser(val id: Long, val displayName: String)
/** Never a data class: access tokens must not appear in generated toString/log output. */
internal class OsmCredentials(val token: String, val user: OsmUser, val expiresAt: Long?,
    val scopes: Set<String> = setOf("read_prefs", "write_notes"))
data class OsmNoteComment(val userId: Long?, val action: String, val text: String, val date: Long)
data class OsmRemoteNote(val id: Long, val latitude: Double, val longitude: Double,
    val status: String, val createdAt: Long, val comments: List<OsmNoteComment>)
data class NoteAttempt(val userId: Long, val latitude: Double, val longitude: Double,
    val text: String, val submittedAt: Long)
data class OAuthTransaction(val clientId: String, val state: String, val startedAt: Long)

object OsmSafetyRules {
    const val REDIRECT_URI = "com.atay.iz:/oauth2redirect"
    fun isExactRedirect(value: String?): Boolean = runCatching {
        val uri = URI(requireNotNull(value))
        uri.scheme == "com.atay.iz" && uri.rawAuthority == null &&
            uri.rawPath == "/oauth2redirect" && uri.rawFragment == null
    }.getOrDefault(false)
    fun reconcile(attempt: NoteAttempt, notes: List<OsmRemoteNote>): OsmRemoteNote? = notes.filter { note ->
        note.createdAt >= attempt.submittedAt - 120_000 &&
            note.createdAt <= attempt.submittedAt + 600_000 && matchesIdentity(attempt, note)
    }.distinctBy { it.id }.singleOrNull()

    /** A direct create response already identifies the operation; the phone clock is irrelevant. */
    fun matchesIdentity(attempt: NoteAttempt, note: OsmRemoteNote): Boolean {
        val first = note.comments.firstOrNull()
        return note.id > 0 && note.status in setOf("open", "closed", "hidden") &&
            kotlin.math.abs(note.latitude - attempt.latitude) <= 0.000001 &&
            kotlin.math.abs(note.longitude - attempt.longitude) <= 0.000001 &&
            first?.userId == attempt.userId && first.action == "opened" && first.text == attempt.text
    }

    fun validCallback(pending: OAuthTransaction, clientId: String, state: String?, redirect: String, now: Long): Boolean =
        pending.clientId == clientId && pending.state.isNotBlank() && pending.state == state &&
            redirect == REDIRECT_URI && now - pending.startedAt in 0..600_000
}
