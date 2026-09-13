package org.iz.navigation.data

import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.weather.routeTravelSpeedKmh

data class CollectionSummary(val journeyCount: Int, val distanceMeters: Double, val elapsedMillis: Long,
    val movingMillis: Long, val stoppedMillis: Long)

/** Shared validation for interactive saves, snapshots and untrusted manual backups. */
object TravelLibraryRules {
    const val MAX_ITEMS = 10_000
    const val MAX_SEGMENTS = 1_000
    const val MAX_TRACK_POINTS = 100_000
    const val MAX_TOTAL_POINTS = 500_000
    const val MAX_MEMBERSHIPS = 100_000

    fun validId(id: String) {
        require(id.isNotBlank() && id.length <= 200 && id.none { it.code < 32 }) { "Kayıt kimliği geçersiz." }
    }

    fun validName(name: String) {
        require(name.isNotBlank() && name.length <= 300 && name.none { it.code < 32 }) { "Ad 1–300 karakter olmalı." }
    }

    fun normalizePlan(plan: SavedRoutePlan): SavedRoutePlan {
        validId(plan.id); validName(plan.name)
        require(plan.createdAt >= 0 && plan.updatedAt >= plan.createdAt) { "Rota kayıt tarihleri geçersiz." }
        require(plan.stops.size in 2..5) { "Kaydedilen rota 2–5 durak içermeli." }
        plan.stops.forEach { validName(it.label) }
        return plan.copy(stops = plan.stops.toList(), travelSpeedKmh = routeTravelSpeedKmh(plan.transport, plan.travelSpeedKmh))
    }

    fun normalizeTrack(track: ImportedTrack): ImportedTrack {
        validId(track.id); validName(track.name)
        require(track.createdAt >= 0) { "GPX kayıt tarihi geçersiz." }
        require(track.segments.size in 1..MAX_SEGMENTS) { "GPX en fazla 1000 bölüm içerebilir." }
        require(track.segments.sumOf { it.points.size.toLong() } <= MAX_TRACK_POINTS) { "GPX en fazla 100000 nokta içerebilir." }
        val segments = track.segments.map { segment ->
            require(segment.name.length <= 300 && segment.trackName.length <= 300 && segment.trackIndex in 0..99) { "GPX bölüm bilgileri geçersiz." }
            val points = segment.points.filterIndexed { index, point -> index == 0 ||
                point.latitude != segment.points[index - 1].latitude || point.longitude != segment.points[index - 1].longitude }
            require(points.size >= 2) { "Her GPX bölümünde en az iki farklı konum gerekli." }
            segment.copy(points = points)
        }
        require(segments.groupBy { it.trackIndex }.values.all { group -> group.map { it.trackName }.distinct().size == 1 }) { "GPX iz grupları tutarsız." }
        return track.copy(segments = segments)
    }

    fun validateCollection(collection: JourneyCollection) {
        validId(collection.id); validName(collection.name)
        require(collection.createdAt >= 0 && collection.updatedAt >= collection.createdAt) { "Gezi kayıt tarihleri geçersiz." }
    }

    fun isEligible(journey: Journey): Boolean = journey.status == JourneyStatus.CONFIRMED &&
        journey.expiresAt == null && journey.endedAt != null && journey.endedAt >= journey.startedAt

    fun validate(snapshot: DiarySnapshot) {
        fun unique(ids: List<String>) {
            require(ids.size <= MAX_ITEMS && ids.distinct().size == ids.size) { "Çok fazla veya yinelenen kütüphane kaydı." }
            ids.forEach(::validId)
        }
        unique(snapshot.savedPlans.map { it.id }); unique(snapshot.importedTracks.map { it.id }); unique(snapshot.collections.map { it.id })
        snapshot.savedPlans.forEach(::normalizePlan)
        require(snapshot.importedTracks.sumOf { track -> track.segments.sumOf { it.points.size.toLong() } } <= MAX_TOTAL_POINTS) { "Kütüphanede çok fazla GPX noktası var." }
        snapshot.importedTracks.forEach(::normalizeTrack)
        snapshot.collections.forEach(::validateCollection)
        require(snapshot.memberships.size <= MAX_MEMBERSHIPS) { "Gezilerde çok fazla yolculuk var." }
        val collections = snapshot.collections.map { it.id }.toSet()
        val journeys = snapshot.journeys.filter(::isEligible).map { it.id }.toSet()
        require(snapshot.memberships.all { it.collectionId in collections && it.journeyId in journeys && it.sortOrder in 0 until MAX_MEMBERSHIPS.toLong() }) { "Gezi yalnız tamamlanmış, kalıcı yolculuklar içerebilir." }
        require(snapshot.memberships.map { it.collectionId to it.journeyId }.distinct().size == snapshot.memberships.size) { "Gezide yinelenen yolculuk var." }
        require(snapshot.memberships.map { it.collectionId to it.sortOrder }.distinct().size == snapshot.memberships.size) { "Gezi sırası belirsiz." }
    }

    fun collectionJourneys(collectionId: String, snapshot: DiarySnapshot): List<Journey> {
        val eligible = snapshot.journeys.filter(::isEligible).associateBy { it.id }
        return snapshot.memberships.filter { it.collectionId == collectionId }.sortedBy { it.sortOrder }
            .mapNotNull { eligible[it.journeyId] }.distinctBy { it.id }
    }

    fun collectionSummary(collectionId: String, snapshot: DiarySnapshot): CollectionSummary {
        val journeys = collectionJourneys(collectionId, snapshot)
        val byJourney = snapshot.points.groupBy { it.journeyId }
        val stats = journeys.map { JourneyStatistics.calculate(it, byJourney[it.id].orEmpty()) }
        return CollectionSummary(journeys.size, stats.sumOf { it.distanceMeters }, stats.sumOf { it.elapsedMillis },
            stats.sumOf { it.movingMillis }, stats.sumOf { it.stoppedMillis })
    }
}
