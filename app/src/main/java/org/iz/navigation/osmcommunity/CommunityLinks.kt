package org.iz.navigation.osmcommunity

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object CommunityLinks {
    private val website = "https://www.openstreetmap.org".toHttpUrl()

    fun profile(displayName: String): String = userPath(displayName).build().toString()
    fun follow(displayName: String): String = userPath(displayName).addPathSegment("follow").build().toString()
    fun following(): String = website.newBuilder().addPathSegment("dashboard").build().toString()
    fun editProfile(): String = website.newBuilder().addPathSegments("profile/description").build().toString()
    // The web mute action is a POST requiring the site's own confirmation/session.
    fun mute(displayName: String): String = profile(displayName)
    fun mutedInbox(): String = website.newBuilder().addPathSegments("messages/muted").build().toString()

    fun safeExternalUrl(value: String): String? {
        if (!value.startsWith("https://", ignoreCase = true) && !value.startsWith("http://", ignoreCase = true)) return null
        if (value.any { it <= ' ' || it == '\u007f' || it == '\\' }) return null
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return url.toString()
    }

    fun safeImageUrl(value: String?): String? = value?.let(::safeExternalUrl)?.takeIf { it.startsWith("https://") }

    private fun userPath(displayName: String) = website.newBuilder().addPathSegment("user").apply {
        require(displayName.isNotBlank() && displayName != "." && displayName != "..") { "OSM kullanıcı adı geçersiz." }
        addPathSegment(displayName)
    }
}
