package org.iz.navigation.navigation

internal fun NavigationState.navigationReplacementKey(): String? =
    if (route == null && trackFollow == null) null else
        "$sessionId/$routeRevision/${route?.id}/${trackFollow?.track?.id}/${trackFollow?.selection}"

internal fun NavigationState.trackReplacementKey(): String? =
    if (trackFollow == null) null else navigationReplacementKey()

/** One explicit user approval, scoped to the GPX session visible at confirmation. */
internal class TrackReplacementConsent {
    private var approvedKey: String? = null

    fun approve(key: String?) { approvedKey = key }

    fun consume(currentKey: String?): Boolean {
        val expected = approvedKey
        approvedKey = null
        if (currentKey == null) return false
        check(expected == currentKey) { "GPX takibi değişti. Rotayı başlatmak için yeniden onayla." }
        return true
    }
}
