package org.iz.navigation.watch

/** Permission identity follows the device API, including Wear OS 6 after an app update. */
internal object WatchHealthPermissions {
    fun foreground(api: Int): Set<String> = setOf(
        if (api >= 36) "android.permission.health.READ_HEART_RATE" else "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION",
    )
    fun background(api: Int): String? = when {
        api >= 36 -> "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        api >= 33 -> "android.permission.BODY_SENSORS_BACKGROUND"
        else -> null
    }
    fun required(api: Int): Set<String> = foreground(api) + listOfNotNull(background(api))
    fun hasRequired(api: Int, granted: (String) -> Boolean): Boolean = required(api).all(granted)
}
