package org.iz.navigation.data

import kotlin.math.*

/** Pure recording/summary rules, shared by the UI and recording service. */
object DiaryRules {
    const val TEMPORARY_LIFETIME_MILLIS = 24 * 60 * 60 * 1000L
    const val DEFAULT_STOP_MILLIS = 10 * 60 * 1000L
    const val MAX_CONTIGUOUS_GAP_MILLIS = 120_000L
    const val MAX_ACCURACY_METERS = 100f

    fun isExpired(journey: Journey, now: Long): Boolean =
        journey.status == JourneyStatus.TEMPORARY &&
            (journey.expiresAt ?: (journey.startedAt + TEMPORARY_LIFETIME_MILLIS)) <= now

    fun isUsablePoint(point: TrackPoint): Boolean =
        point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
            point.longitude.isFinite() && point.longitude in -180.0..180.0 &&
            point.accuracy.isFinite() && point.accuracy in 0f..MAX_ACCURACY_METERS &&
            (point.speed == null || point.speed.isFinite() && point.speed >= 0) &&
            (point.altitude == null || point.altitude.isFinite())

    fun metersBetween(a: TrackPoint, b: TrackPoint): Double {
        val latDelta = Math.toRadians(b.latitude - a.latitude)
        val lonDelta = Math.toRadians(b.longitude - a.longitude)
        val haversine = sin(latDelta / 2).pow(2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lonDelta / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    fun connects(a: TrackPoint, b: TrackPoint): Boolean {
        val elapsed = b.recordedAt - a.recordedAt
        return a.journeyId == b.journeyId && !b.breakBefore &&
            isUsablePoint(a) && isUsablePoint(b) && elapsed in 1..MAX_CONTIGUOUS_GAP_MILLIS &&
            metersBetween(a, b) / (elapsed / 1000.0) <= 100.0
    }

    fun distanceMeters(points: List<TrackPoint>): Double =
        points.groupBy { it.journeyId }.values.sumOf { route ->
            route.sortedBy { it.recordedAt }.zipWithNext().sumOf { (a, b) ->
                if (connects(a, b)) {
                    val distance = metersBetween(a, b)
                    // Avoid counting small GPS fluctuations while the user is stationary.
                    if (distance >= max(3.0, min(a.accuracy, b.accuracy) * 0.4)) distance else 0.0
                } else 0.0
            }
        }

    fun stoppedDurationMillis(points: List<TrackPoint>): Long =
        points.groupBy { it.journeyId }.values.sumOf { route ->
            route.sortedBy { it.recordedAt }.zipWithNext().sumOf { (a, b) ->
                val elapsed = b.recordedAt - a.recordedAt
                val distance = metersBetween(a, b)
                val positionSpeed = distance / (elapsed.coerceAtLeast(1) / 1000.0)
                val movementThreshold = max(5.0, min(a.accuracy, b.accuracy).toDouble())
                val positionShowsMovement = distance >= movementThreshold && positionSpeed >= 0.75
                val stopped = if (a.speed != null && b.speed != null) {
                    // Some providers report zero speed while reliable coordinates keep moving.
                    a.speed < 0.75f && b.speed < 0.75f && !positionShowsMovement
                } else {
                    distance < movementThreshold && positionSpeed < 0.75
                }
                if (connects(a, b) && stopped) elapsed else 0L
            }
        }

    fun confirmed(journey: Journey, transport: Transport): Journey = journey.copy(
        status = JourneyStatus.CONFIRMED, transport = transport, expiresAt = null,
        stepCount = journey.stepCount.takeIf { transport.supportsSteps && journey.transport.supportsSteps },
    )

    /** Restoring a backup must never silently start a location recording. */
    fun restored(journey: Journey, now: Long): Journey = if (journey.endedAt == null) {
        journey.copy(endedAt = now.coerceAtLeast(journey.startedAt), interrupted = true)
    } else journey

    fun validate(snapshot: DiarySnapshot) {
        fun ids(values: List<String>): Set<String> {
            require(values.all { it.isNotBlank() && it.length <= 200 && it.none { char -> char.code < 32 } }) { "Geçersiz kayıt kimliği." }
            require(values.toSet().size == values.size) { "Yinelenen kayıt kimliği." }
            return values.toSet()
        }
        val journeys = ids(snapshot.journeys.map { it.id })
        require(snapshot.journeys.all { it.stepCount == null || (it.transport.supportsSteps && it.stepCount >= 0) }) {
            "Adım sayısı yalnızca yürüyüş ve koşu için sıfır veya pozitif olabilir."
        }
        ids(snapshot.points.map { it.id })
        val places = ids(snapshot.places.map { it.id })
        val visits = ids(snapshot.visits.map { it.id })
        val photos = ids(snapshot.photos.map { it.id })
        val photosById = snapshot.photos.associateBy { it.id }
        ids(snapshot.drafts.map { it.id })
        require(snapshot.journeys.all { it.endedAt == null || it.endedAt >= it.startedAt }) { "Geçersiz yolculuk zamanı." }
        require(snapshot.points.all { it.journeyId in journeys && isUsablePoint(it) }) { "Geçersiz rota noktası." }
        fun validCoordinate(lat: Double?, lon: Double?) =
            (lat == null && lon == null) ||
                (lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
        fun validRating(rating: Int?) = rating == null || rating in 1..5
        require(snapshot.places.all { it.name.isNotBlank() && validCoordinate(it.latitude, it.longitude) }) { "Geçersiz yer." }
        require(snapshot.visits.all { it.placeId in places && (it.journeyId == null || it.journeyId in journeys) && validRating(it.rating) }) { "Geçersiz ziyaret bağlantısı." }
        require(snapshot.photos.all {
            (it.journeyId == null || it.journeyId in journeys) && (it.visitId == null || it.visitId in visits) &&
                (it.journeyId != null || it.visitId != null) && validCoordinate(it.latitude, it.longitude) &&
                isSafePhotoPath(it.relativePath)
        }) { "Geçersiz fotoğraf bağlantısı veya yolu." }
        require(snapshot.drafts.map { it.visitId }.toSet().size == snapshot.drafts.size) { "Bir ziyarete bir taslak kaydedilebilir." }
        require(snapshot.drafts.all { draft ->
            draft.visitId in visits && validRating(draft.rating) && draft.photoIds.all { id ->
                id in photos && photosById[id]?.visitId == draft.visitId
            }
        }) { "Geçersiz paylaşım taslağı." }
        HealthRules.validateSnapshot(snapshot)
        require(snapshot.mapEdits.map { it.id }.toSet().size == snapshot.mapEdits.size)
        snapshot.mapEdits.forEach(::validateMapEdit)
        WatchHealthRules.validateSnapshot(snapshot.journeys, snapshot.watchHealthSessions, snapshot.watchHealthSamples)
    }

    fun isSafePhotoPath(path: String): Boolean =
        path.startsWith("photos/") && path.length <= 250 &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." } &&
            '\\' !in path && ':' !in path && '\u0000' !in path
}
