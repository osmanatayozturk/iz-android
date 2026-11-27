package org.iz.navigation.data

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** ZIP entry names have already been mapped to private files with generated names. */
internal class StagedBackupBulkRows private constructor(
    private val directory: Map<String, BackupBulkGroup>,
    private val files: Map<String, File>,
    private val check: () -> Unit,
) : BackupBulkRows {
    fun <T> metadata(type: String, limit: Int, parse: (JSONObject) -> T): List<T>? {
        val group = directory[type] ?: return null
        // These rows are validated and inserted a page at a time inside the replacement transaction.
        if (type == "points" || type == "healthSamples" || type == "watchHealthSamples") return emptyList()
        require(group.records <= limit) { "Yedek tablosunda çok fazla kayıt var: $type." }
        val models = ArrayList<T>(group.records)
        for (part in group.parts) records(part).mapTo(models, parse)
        return models
    }

    fun libraryTracks() = BackupLibraryPages.read(directory, ::records)

    override suspend fun forEachPoints(consume: suspend (List<TrackPoint>) -> Unit) =
        readPages("points", BackupJson::readPoint, consume)
    override suspend fun forEachHealth(consume: suspend (List<JourneyHealthSample>) -> Unit) =
        readPages("healthSamples", BackupJson::readHealthSample, consume)
    override suspend fun forEachWatch(consume: suspend (List<WatchHealthSample>) -> Unit) =
        readPages("watchHealthSamples", BackupJson::readWatchSample, consume)

    private fun records(part: BackupBulkPart): List<JSONObject> =
        files.getValue(part.path).inputStream().buffered().use { BackupBulkPages.readPage(it, part, check) }

    private suspend fun <T> readPages(type: String, parse: (JSONObject) -> T, use: suspend (List<T>) -> Unit) {
        for (part in directory.getValue(type).parts) {
            currentCoroutineContext().ensureActive()
            use(records(part).map(parse))
        }
    }

    companion object {
        suspend fun open(manifest: JSONObject, files: Map<String, File>): StagedBackupBulkRows {
            val groups = BackupBulkPages.parse(manifest)
            val expected = groups.flatMap { it.parts }
            require(files.keys == expected.map { it.path }.toSet()) { "Yedek sayfası eksik veya beklenmeyen sayfa var." }
            require(expected.all { files.getValue(it.path).length() == it.bytes }) { "Yedek sayfasının boyutu dizinle eşleşmiyor." }
            val context = currentCoroutineContext()
            return StagedBackupBulkRows(groups.associateBy { it.type }, files) { context.ensureActive() }
        }
    }
}
