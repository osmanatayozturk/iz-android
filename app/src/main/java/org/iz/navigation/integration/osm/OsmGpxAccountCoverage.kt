package org.iz.navigation.integration.osm

import org.iz.navigation.integration.OsmDirectedPoint
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.osmcommunity.CommunitySession

internal fun PreparedOsmGpxTrace.geometry() = OsmGpxRemoteData(
    segments.map { segment -> segment.map { OsmDirectedPoint(it.latitude, it.longitude) } }, sha256,
)

internal class OsmGpxAccountCoverage(
    private val cache: OsmGpxCoverageCache,
    private val accountId: Long,
    private val traces: List<OsmCachedTrace>,
    /** Only an unambiguous full byte digest can reconcile an interrupted request. */
    val byDigest: Map<String, Long>,
) {
    val submittedDigests = traces.mapTo(HashSet()) { it.sha256 }

    fun nearby(planned: List<Pair<Long, PreparedOsmGpxTrace>>): List<List<OsmDirectedPoint>> {
        val areas = planned.map { (_, trace) ->
            OsmCoverageBounds().also { area ->
                for (segment in trace.coverageSegments) for (fix in segment) {
                    area.add(fix.point.latitude, fix.point.longitude)
                }
            }
        }
        val retained = mutableListOf<List<OsmDirectedPoint>>()
        var count = 0
        for (trace in traces) cache.nearby(accountId, trace, areas) { block ->
            count += block.size
            require(count <= MAX_OSM_POINTS) { "Local coverage density exceeds processing limit" }
            retained += block
        }
        return retained
    }
}

internal class OsmGpxCoverageLoader(
    private val store: OsmGpxReceiptStore,
    private val gateway: OsmGpxHistoryGateway,
    private val loadJourney: suspend (String) -> Pair<Long, PreparedOsmGpxTrace>?,
) {
    suspend fun load(session: CommunitySession, entries: List<OsmGpxHistoryEntry>,
        acknowledgedHere: Set<Long>, current: () -> Boolean): OsmGpxAccountCoverage {
        require(entries.size <= MAX_OSM_TRACES && entries.map { it.id }.distinct().size == entries.size)
        val records = store.inventory(session.userId)
        val confirmed = records.filter { it.verified && it.traceId != null }.associateBy { requireNotNull(it.traceId) }
        val listed = entries.associateBy { it.id }
        // Older versions could record RECEIVED from a filename. Until its ID is listed,
        // there is no geometry proof for that receipt and a recent upload may still be pending.
        check(records.none { it.status == OsmGpxReceiptStatus.RECEIVED && !it.verified && it.traceId !in listed })
        val ids = listed.keys + confirmed.keys
        require(ids.size <= MAX_OSM_TRACES)
        check(current())
        store.coverageCache.prepareAccount(session.userId, ids, current)
        val geometry = mutableListOf<OsmCachedTrace>()
        val matches = mutableMapOf<String, Long>()
        val ambiguous = mutableSetOf<String>()
        for (id in ids) {
            check(current())
            val remote = listed[id]
            check(remote?.pending != true || id in acknowledgedHere && id in confirmed) { "pending-trace" }
            val own = confirmed[id]
            val redownloadable = remote != null && !remote.pending
            val cached = store.coverageCache.inspect(session.userId, id, redownloadable, current)
            val local = if (cached == null && own?.journeyId != null &&
                (remote == null || remote.fileName == org.iz.navigation.integration.osmGpxFileName(own.sha256))) {
                loadJourney(own.journeyId)?.second?.takeIf { it.sha256 == own.sha256 }?.geometry()
            } else null
            val geometryFile = cached ?: run {
                val data = local ?: run {
                    check(remote != null && current())
                    gateway.data(session.token, id)
                }
                check(current())
                store.coverageCache.write(session.userId, id, data, redownloadable, current)
            }
            geometry += geometryFile
            if (remote != null && matches.put(geometryFile.sha256, id) != null) ambiguous += geometryFile.sha256
        }
        matches.keys.removeAll(ambiguous)
        val unknowns = records.filter { it.status == OsmGpxReceiptStatus.UNKNOWN }
        check(unknowns.all { it.sha256 in matches }) { "unresolved-receipt" }
        check(current())
        for (record in unknowns) {
            check(current())
            store.resolveUnknown(session.userId, record, matches.getValue(record.sha256))
        }
        store.coverageCache.prune(session.userId, ids, current)
        return OsmGpxAccountCoverage(store.coverageCache, session.userId, geometry, matches)
    }
}
