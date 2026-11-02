package org.iz.navigation.wear

import org.iz.navigation.wearprotocol.WearSnapshot

/** Reachable capability discovery is bounded and each node contributes only its highest version. */
internal fun highestReachableWatchVersions(
    capabilities: Iterable<Pair<Int, Iterable<String>>>, currentVersion: Int, maxPeers: Int,
): Map<String, Int> {
    val versions = linkedMapOf<String, Int>()
    for ((version, nodes) in capabilities) {
        if (version !in 1..currentVersion) continue
        for (node in nodes) {
            if (node !in versions && versions.size >= maxPeers) return emptyMap()
            versions[node] = maxOf(versions[node] ?: 0, version)
        }
    }
    return versions
}

/** Caller has applied the existing recent-command TTL; current version is already published first. */
internal fun wearCompatibilityVersions(
    advertised: Map<String, Int>, recent: Map<String, Int>, currentVersion: Int,
): List<Int> {
    val versions = advertised.filterValues { it in 1..currentVersion }.toMutableMap()
    recent.forEach { (node, version) ->
        if (version in 1..currentVersion) versions[node] = maxOf(versions[node] ?: 0, version)
    }
    return versions.values.filter { it in 1 until currentVersion }.distinct().sorted()
}

/** Compatibility publishing must reuse the prepared/deferred state for modern peers. */
internal suspend fun publishCompatibleWearSnapshots(
    state: WearSnapshot, versions: List<Int>, currentVersion: Int,
    legacySnapshot: suspend () -> WearSnapshot, publish: suspend (WearSnapshot, Int) -> Unit,
) {
    versions.filter { it in 6 until currentVersion }.distinct().sorted().forEach { publish(state, it) }
    val legacyVersions = versions.filter { it in 1..5 && it != currentVersion }.distinct().sorted()
    if (legacyVersions.isNotEmpty()) {
        val legacy = legacySnapshot()
        legacyVersions.forEach { publish(legacy, it) }
    }
}
