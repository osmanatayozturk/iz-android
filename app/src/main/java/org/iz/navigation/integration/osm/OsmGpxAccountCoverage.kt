package org.iz.navigation.integration.osm

import org.iz.navigation.integration.OsmDirectedPoint
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.osmcommunity.CommunitySession

internal fun PreparedOsmGpxTrace.geometry() = OsmGpxRemoteData(
    segments.map { segment -> segment.map { OsmDirectedPoint(it.latitude, it.longitude) } }, sha256,
)

internal class OsmListedGpxFormatException(val rowNumber: Int) : java.io.IOException()

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
        acknowledgedHere: Set<Long>, current: () -> Boolean, phase: (OsmHistoryPhase) -> Unit): OsmGpxAccountCoverage {
        require(entries.size <= MAX_OSM_TRACES && entries.map { it.id }.distinct().size == entries.size)
        phase(OsmHistoryPhase.RECEIPTS)
        val records = store.inventory(session.userId)
        val confirmed = records.filter { it.verified && it.traceId != null }.associateBy { requireNotNull(it.traceId) }
        val listed = entries.associateBy { it.id }
        // Old releases also generated RECEIVED from a filename. A missing legacy ID
        // may be rebound only to one matching GPX digest in the complete account history.
        val missingLegacyIds = records.filter { it.status == OsmGpxReceiptStatus.RECEIVED && !it.verified && it.traceId !in listed }
        val ids = listed.keys + confirmed.keys
        require(ids.size <= MAX_OSM_TRACES)
        check(current())
        phase(OsmHistoryPhase.CACHE)
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
            phase(OsmHistoryPhase.CACHE)
            val cached = store.coverageCache.inspect(session.userId, id, redownloadable, current)
                ?: if (remote == null && own != null)
                    store.coverageCache.inspectUnlistedSubmission(session.userId, id, own.sha256, current)
                else null
            val local = if (cached == null && own?.journeyId != null &&
                (remote == null || remote.fileName == org.iz.navigation.integration.osmGpxFileName(own.sha256))) {
                phase(OsmHistoryPhase.LOCAL_TRACE)
                loadJourney(own.journeyId)?.second?.takeIf { it.sha256 == own.sha256 }?.geometry()
            } else null
            val geometryFile = cached ?: run {
                val data = local ?: run {
                    check(remote != null) { "missing-verified-geometry" }
                    check(current())
                    phase(OsmHistoryPhase.DOWNLOAD)
                    try {
                        gateway.data(session.token, id)
                    } catch (_: OsmGpxGeometryFormatException) {
                        // Both screens use this immutable, newest-first account inventory.
                        throw OsmListedGpxFormatException(entries.indexOf(remote) + 1)
                    }
                }
                check(current())
                phase(OsmHistoryPhase.CACHE)
                store.coverageCache.write(session.userId, id, data, redownloadable, current)
            }
            geometry += geometryFile
            if (remote != null && matches.put(geometryFile.sha256, id) != null) ambiguous += geometryFile.sha256
        }
        matches.keys.removeAll(ambiguous)
        phase(OsmHistoryPhase.RECEIPTS)
        val unknowns = records.filter { it.status == OsmGpxReceiptStatus.UNKNOWN }
        check(missingLegacyIds.all { it.sha256 in matches }) { "missing-legacy-receipt" }
        check(unknowns.all { it.sha256 in matches }) { "unresolved-receipt" }
        check(current())
        for (record in unknowns + missingLegacyIds) {
            check(current())
            store.verifyRemoteReceipt(session.userId, record, matches.getValue(record.sha256))
        }
        phase(OsmHistoryPhase.CACHE)
        store.coverageCache.prune(session.userId, ids, current)
        return OsmGpxAccountCoverage(store.coverageCache, session.userId, geometry, matches)
    }
}
