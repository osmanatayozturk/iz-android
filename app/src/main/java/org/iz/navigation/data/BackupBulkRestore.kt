package org.iz.navigation.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Streams complete pages sequentially and propagates any failure from the consumer. */
internal interface BackupBulkRows {
    suspend fun forEachPoints(consume: suspend (List<TrackPoint>) -> Unit)
    suspend fun forEachHealth(consume: suspend (List<JourneyHealthSample>) -> Unit)
    suspend fun forEachWatch(consume: suspend (List<WatchHealthSample>) -> Unit)
}

internal class SnapshotBackupBulkRows(private val snapshot: DiarySnapshot) : BackupBulkRows {
    override suspend fun forEachPoints(consume: suspend (List<TrackPoint>) -> Unit) =
        consumePages(snapshot.points, consume)
    override suspend fun forEachHealth(consume: suspend (List<JourneyHealthSample>) -> Unit) =
        consumePages(snapshot.healthSamples, consume)
    override suspend fun forEachWatch(consume: suspend (List<WatchHealthSample>) -> Unit) =
        consumePages(snapshot.watchHealthSamples, consume)

    private suspend fun <T> consumePages(rows: List<T>, consume: suspend (List<T>) -> Unit) {
        for (start in rows.indices step 512) {
            consume(rows.subList(start, minOf(start + 512, rows.size)))
        }
    }
}

/** The caller has inserted the parents and owns the Room replacement transaction. */
internal suspend fun BackupBulkRows.restoreInto(
    journeys: List<Journey>,
    sessions: List<WatchHealthSession>,
    diary: DiaryDao,
    watch: WatchHealthDao,
    restoredAt: Long,
) {
    val byJourney = journeys.associateBy(Journey::id)
    val bySession = sessions.associateBy(WatchHealthSession::id)

    forEachPoints { page ->
        currentCoroutineContext().ensureActive()
        require(page.all { point ->
            point.id.isNotBlank() && point.id.length <= 200 && point.id.none { it.code < 32 } &&
                point.journeyId in byJourney && DiaryRules.isUsablePoint(point)
        }) { "Geçersiz rota noktası." }
        if (page.isNotEmpty()) diary.restorePoints(page)
    }
    forEachHealth { page ->
        currentCoroutineContext().ensureActive()
        require(page.all { sample ->
            val journey = byJourney[sample.journeyId]
            journey != null && journey.status == JourneyStatus.CONFIRMED &&
                HealthRules.isValid(sample.imported()) && sample.startAt >= journey.startedAt &&
                (journey.endedAt == null || sample.startAt < journey.endedAt && sample.endAt <= journey.endedAt)
        }) { "Invalid health source, measurement or journey association." }
        if (page.isNotEmpty()) diary.restoreHealthSamples(page)
    }
    forEachWatch { page ->
        currentCoroutineContext().ensureActive()
        require(page.all { sample ->
            val session = bySession[sample.sessionId]
            val journey = byJourney[sample.journeyId]
            session != null && session.journeyId == sample.journeyId && sample.startAt >= session.createdAt &&
                journey != null && WatchHealthRules.belongsTo(sample, journey, Long.MAX_VALUE)
        }) { "Invalid direct watch health data." }
        if (page.isNotEmpty()) watch.restoreSamples(page)
    }

    // Check primary keys on every original row before dropping measurements outside a newly closed trip.
    for (journey in journeys) {
        if (journey.endedAt != null) continue
        val end = requireNotNull(DiaryRules.restored(journey, restoredAt).endedAt)
        diary.pruneHealthOutsideJourney(journey.id, journey.startedAt, end)
        watch.pruneOutsideJourney(journey.id, journey.startedAt, end)
    }
}
