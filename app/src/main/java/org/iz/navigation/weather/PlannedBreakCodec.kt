package org.iz.navigation.weather

import org.json.JSONObject

/** Optional additive metadata; malformed values are never silently converted into zero. */
internal fun JSONObject.plannedBreakSeconds(): Int {
    if (!has("plannedBreakSeconds")) return 0
    val raw = get("plannedBreakSeconds")
    require(raw is Byte || raw is Short || raw is Int || raw is Long) { "Mola süresi tamsayı olmalı." }
    val seconds = (raw as Number).toLong()
    require(seconds in 0..14_400) { "Mola süresi 0–240 dakika olmalı." }
    return seconds.toInt()
}

internal fun JSONObject.withPlannedBreak(stop: RouteStop): JSONObject = apply {
    if (stop.plannedBreakSeconds > 0) put("plannedBreakSeconds", stop.plannedBreakSeconds)
}
