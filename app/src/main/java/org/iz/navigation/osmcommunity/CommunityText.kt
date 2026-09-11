package org.iz.navigation.osmcommunity

/** OSM counts Unicode characters, not the UTF-16 units used by String.length. */
internal object CommunityText {
    fun titleLength(value: String): Int = value.codePointCount(0, value.length)
    fun truncateTitle(value: String): String = if (titleLength(value) <= 255) value
        else value.substring(0, value.offsetByCodePoints(0, 255))
}
