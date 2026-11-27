package org.iz.navigation.integration.osm

import java.io.DataInput
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.iz.navigation.integration.OsmDirectedPoint

internal const val MAX_OSM_TRACES = 10_000
internal const val MAX_OSM_POINTS = 300_000
private const val BLOCK_POINTS = 256
private const val RADIUS = 6_371_000.0
private const val DEFAULT_GEOMETRY_QUOTA = 128L * 1024 * 1024
private const val MIN_USABLE_SPACE = 32L * 1024 * 1024
private const val REDOWNLOADABLE_SUFFIX = ".remote.geom"
private val TEMP_GEOMETRY_NAME = Regex("geometry-[0-9]+\\.tmp")

/** Cartesian bounds remain conservative across the date line and near the poles. */
internal class OsmCoverageBounds {
    private val lo = DoubleArray(3) { Double.POSITIVE_INFINITY }
    private val hi = DoubleArray(3) { Double.NEGATIVE_INFINITY }

    fun add(lat: Double, lon: Double) {
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        val a = Math.toRadians(lat)
        val b = Math.toRadians(lon)
        val circle = RADIUS * cos(a)
        extend(0, circle * cos(b))
        extend(1, circle * sin(b))
        extend(2, RADIUS * sin(a))
    }

    private fun extend(i: Int, value: Double) {
        lo[i] = minOf(lo[i], value)
        hi[i] = maxOf(hi[i], value)
    }

    // More than the matcher corridor plus the maximum sagitta of its 50 km history leg.
    fun overlaps(other: OsmCoverageBounds) = lo.indices.all {
        lo[it] <= other.hi[it] + 500 && other.lo[it] <= hi[it] + 500
    }

    fun write(out: DataOutput) {
        for (i in lo.indices) { out.writeDouble(lo[i]); out.writeDouble(hi[i]) }
    }

    companion object {
        fun read(input: DataInput) = OsmCoverageBounds().also { box ->
            for (i in box.lo.indices) {
                box.lo[i] = input.readDouble()
                box.hi[i] = input.readDouble()
                require(!box.lo[i].isNaN() && !box.hi[i].isNaN())
            }
        }
    }
}

internal data class OsmCachedTrace(val id: Long, val sha256: String, val bounds: OsmCoverageBounds)

/** Per-trace geometry in private, non-backed-up storage. Every block has its own spatial bounds. */
internal class OsmGpxCoverageCache(
    private val root: File,
    private val quotaBytes: Long = DEFAULT_GEOMETRY_QUOTA,
) {
    init { require(quotaBytes > 0L) }

    private val verifiedFiles = mutableMapOf<File, Pair<Long, Long>>()
    private var inventory: DiskInventory? = null
    private var observedRevision = -1L

    private fun requireCurrent(current: () -> Boolean) {
        if (!current()) throw CancellationException("OSM geometry cache request changed")
    }

    private fun checksum(file: RandomAccessFile, length: Long, current: () -> Boolean): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var remaining = length
        file.seek(0)
        while (remaining > 0) {
            requireCurrent(current)
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            file.readFully(buffer, 0, count)
            digest.update(buffer, 0, count)
            remaining -= count
        }
        return digest.digest()
    }

    private fun directory(account: Long): File {
        require(account > 0)
        return File(root, account.toString())
    }

    private fun geometryFile(dir: File, id: Long): File {
        require(id > 0)
        val remote = File(dir, "$id$REDOWNLOADABLE_SUFFIX")
        return if (Files.exists(remote.toPath(), NOFOLLOW_LINKS)) remote else File(dir, "$id.geom")
    }

    private fun traceId(name: String): Long? = when {
        name.endsWith(REDOWNLOADABLE_SUFFIX) -> positiveId(name.removeSuffix(REDOWNLOADABLE_SUFFIX))
        name.endsWith(".geom") -> positiveId(name.removeSuffix(".geom"))
        else -> null
    }

    private fun positiveId(text: String): Long? =
        text.toLongOrNull()?.takeIf { it > 0L && it.toString() == text }

    private fun files(dir: File, current: () -> Boolean): List<File> {
        requireCurrent(current)
        if (Files.notExists(dir.toPath(), NOFOLLOW_LINKS)) return emptyList()
        check(Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS))
        return Files.newDirectoryStream(dir.toPath()).use { paths ->
            paths.mapNotNull { path ->
                requireCurrent(current)
                val file = path.toFile()
                when {
                    !Files.isRegularFile(path, NOFOLLOW_LINKS) -> null
                    TEMP_GEOMETRY_NAME.matches(file.name) -> {
                        requireCurrent(current)
                        Files.deleteIfExists(path)
                        null
                    }
                    traceId(file.name) != null -> file
                    else -> null
                }
            }
        }
    }

    /** The verified account inventory is known before any cache miss triggers a download. */
    fun prepareAccount(account: Long, keep: Set<Long>, current: () -> Boolean = { true }) = synchronized(diskLock) {
        requireCurrent(current)
        val dir = directory(account)
        // Unknown uploads and legacy entries stay protected until a full reconciliation.
        pruneFiles(dir, keep, onlyRedownloadable = true, current = current)
        inventory = null // Refresh the ledger and recover temp files left by a stopped process.
        reserve(dir, 0, current)
    }

    private fun reserve(active: File, extraBytes: Long, current: () -> Boolean) {
        requireCurrent(current)
        if (extraBytes > quotaBytes) throw IOException("geometry-quota")
        val existing = diskInventory(current)
        val needed = existing.bytes - (quotaBytes - extraBytes)
        if (needed <= 0L) return
        val others = existing.files.entries.filter { (file, _) ->
            file.parentFile != active && file.name.endsWith(REDOWNLOADABLE_SUFFIX)
        }
        if (others.sumOf { it.value } < needed) throw IOException("geometry-quota")
        var freed = 0L
        for ((file, bytes) in others.sortedWith(compareBy({ it.key.lastModified() }, { it.key.path }))) {
            if (freed >= needed) break
            deleteGeometry(file, current)
            freed += bytes
        }
    }

    private fun diskInventory(current: () -> Boolean): DiskInventory {
        val cached = inventory
        if (cached != null && observedRevision == diskRevision) return cached
        val scanned = DiskInventory()
        if (!Files.notExists(root.toPath(), NOFOLLOW_LINKS)) {
            check(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
            Files.newDirectoryStream(root.toPath()).use { accounts ->
                for (account in accounts) {
                    requireCurrent(current)
                    if (positiveId(account.fileName.toString()) == null || !Files.isDirectory(account, NOFOLLOW_LINKS)) continue
                    for (file in files(account.toFile(), current)) {
                        requireCurrent(current)
                        scanned.put(file, Files.size(file.toPath()))
                    }
                }
            }
        }
        inventory = scanned
        observedRevision = diskRevision
        return scanned
    }

    private fun recordChange(previous: File?, installed: File?, size: Long = 0) {
        if (observedRevision == diskRevision) {
            val current = inventory
            if (previous != null) current?.remove(previous)
            if (installed != null) current?.put(installed, size)
        } else {
            inventory = null
        }
        observedRevision = ++diskRevision
    }

    private fun deleteGeometry(file: File, current: () -> Boolean) {
        requireCurrent(current)
        Files.delete(file.toPath())
        verifiedFiles.remove(file)
        recordChange(file, null)
    }

    private fun header(file: RandomAccessFile, id: Long): OsmCachedTrace {
        require(file.readInt() == 3)
        require(file.readLong() == file.length())
        val sha = file.readUTF().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        return OsmCachedTrace(id, sha, OsmCoverageBounds.read(file))
    }

    fun inspect(account: Long, id: Long, redownloadable: Boolean = false,
                current: () -> Boolean = { true }): OsmCachedTrace? = synchronized(diskLock) {
        requireCurrent(current)
        val dir = directory(account)
        val file = geometryFile(dir, id)
        val trace = inspectFile(file, id, current) ?: return@synchronized null
        if (redownloadable && !file.name.endsWith(REDOWNLOADABLE_SUFFIX)) {
            val remote = File(dir, "$id$REDOWNLOADABLE_SUFFIX")
            val size = Files.size(file.toPath())
            requireCurrent(current)
            Files.move(file.toPath(), remote.toPath(), StandardCopyOption.ATOMIC_MOVE)
            verifiedFiles.remove(file)?.let { verifiedFiles[remote] = it }
            recordChange(file, remote, size)
        }
        trace
    }

    private fun inspectFile(file: File, id: Long, current: () -> Boolean = { true }): OsmCachedTrace? {
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        return try { RandomAccessFile(file, "r").use { input ->
            val stamp = file.lastModified() to input.length()
            require(stamp.second in 128L..(MAX_OSM_POINTS * 80L + 256L))
            if (verifiedFiles[file] != stamp) {
                val actual = checksum(input, input.length() - 32, current)
                val stored = ByteArray(32).also(input::readFully)
                require(MessageDigest.isEqual(actual, stored))
                verifiedFiles[file] = stamp
                input.seek(0)
            }
            header(input, id)
        } }
        catch (_: java.io.EOFException) { null }
        catch (_: IllegalArgumentException) { null }
    }

    fun nearby(account: Long, trace: OsmCachedTrace, areas: List<OsmCoverageBounds>,
               accept: (List<OsmDirectedPoint>) -> Unit) = synchronized(diskLock) {
        val relevant = areas.filter(trace.bounds::overlaps)
        if (relevant.isEmpty()) return@synchronized
        val stored = geometryFile(directory(account), trace.id)
        if (inspectFile(stored, trace.id)?.sha256 != trace.sha256) throw IOException("geometry-missing")
        RandomAccessFile(stored, "r").use { file ->
            check(header(file, trace.id).sha256 == trace.sha256)
            val dataEnd = file.length() - 32
            while (file.filePointer < dataEnd) {
                val count = file.readUnsignedShort().also { require(it in 1..BLOCK_POINTS) }
                val bounds = OsmCoverageBounds.read(file)
                val end = file.filePointer + count * 16L
                require(end <= dataEnd)
                if (relevant.any(bounds::overlaps)) {
                    accept(List(count) { OsmDirectedPoint(file.readDouble(), file.readDouble()) })
                } else file.seek(end)
            }
        }
    }

    fun write(account: Long, id: Long, data: OsmGpxRemoteData, redownloadable: Boolean = false,
              current: () -> Boolean = { true }): OsmCachedTrace = synchronized(diskLock) {
        requireCurrent(current)
        val dir = directory(account)
        val previousFile = geometryFile(dir, id)
        require(data.sha256.matches(Regex("[a-f0-9]{64}")))
        require(data.segments.isNotEmpty() && data.segments.none { it.isEmpty() })
        val points = data.segments.sumOf { it.size.toLong() }
        require(data.segments.size <= MAX_OSM_POINTS && points <= MAX_OSM_POINTS)
        val bytes = encodedSize(data.segments)
        val file = if (redownloadable || previousFile.name.endsWith(REDOWNLOADABLE_SUFFIX))
            File(dir, "$id$REDOWNLOADABLE_SUFFIX") else previousFile
        val bounds = OsmCoverageBounds()
        for (segment in data.segments) {
            var previous: OsmDirectedPoint? = null
            for (i in segment.indices) {
                if (i % BLOCK_POINTS == 0) requireCurrent(current)
                val p = segment[i]
                bounds.add(p.latitude, p.longitude)
                previous?.let { a ->
                    // Short sample steps never approach the matcher's 50 km historical gap limit.
                    if (abs(a.latitude - p.latitude) > 0.2 || abs(a.longitude - p.longitude) > 0.2) {
                        val s = sin(Math.toRadians((p.latitude - a.latitude) / 2))
                        val t = sin(Math.toRadians((p.longitude - a.longitude) / 2))
                        val haversine = s * s + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(p.latitude)) * t * t
                        require(4 * RADIUS * RADIUS * haversine <= 50_000.0 * 50_000.0) { "unverified-gap" }
                    }
                }
                previous = p
            }
        }
        reserve(dir, bytes, current)
        requireCurrent(current)
        Files.createDirectories(dir.toPath())
        check(Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS))
        if (dir.usableSpace < bytes + MIN_USABLE_SPACE) throw IOException("geometry-space")
        requireCurrent(current)
        val temp = File.createTempFile("geometry-", ".tmp", dir)
        try {
            requireCurrent(current)
            FileOutputStream(temp).use { stream ->
                val out = DataOutputStream(stream.buffered())
                out.writeInt(3)
                out.writeLong(0) // filled after all blocks are written
                out.writeUTF(data.sha256)
                bounds.write(out)
                for (segment in data.segments) {
                    var start = 0
                    while (start < segment.size) {
                        requireCurrent(current)
                        val end = minOf(start + BLOCK_POINTS, segment.size)
                        val block = OsmCoverageBounds()
                        for (i in start until end) block.add(segment[i].latitude, segment[i].longitude)
                        out.writeShort(end - start)
                        block.write(out)
                        for (i in start until end) {
                            out.writeDouble(segment[i].latitude)
                            out.writeDouble(segment[i].longitude)
                        }
                        if (end == segment.size) break
                        start = end - 1 // preserve the connecting edge across spatial blocks
                    }
                }
                requireCurrent(current)
                out.flush()
            }
            requireCurrent(current)
            RandomAccessFile(temp, "rw").use { output ->
                val length = output.length()
                output.seek(4)
                output.writeLong(length + 32)
                output.write(checksum(output, length, current))
                requireCurrent(current)
                output.fd.sync()
            }
            check(Files.size(temp.toPath()) == bytes)
            // Replacing an existing version never counts the target as free while the temp exists.
            if (file != previousFile && Files.exists(previousFile.toPath(), NOFOLLOW_LINKS)) {
                requireCurrent(current)
                Files.move(temp.toPath(), previousFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
                verifiedFiles.remove(previousFile)
                recordChange(previousFile, previousFile, bytes)
                requireCurrent(current)
                Files.move(previousFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } else {
                requireCurrent(current)
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            }
            verifiedFiles.remove(previousFile)
            verifiedFiles.remove(file)
            recordChange(previousFile, file, bytes)
        } finally { Files.deleteIfExists(temp.toPath()) }
        OsmCachedTrace(id, data.sha256, bounds)
    }

    private fun encodedSize(segments: List<List<OsmDirectedPoint>>): Long {
        val headerAndChecksum = 4L + 8 + 2 + 64 + 48 + 32
        return headerAndChecksum + segments.sumOf { segment ->
            val blocks = maxOf(1L, (segment.size - 1L + BLOCK_POINTS - 2) / (BLOCK_POINTS - 1))
            blocks * (2 + 48) + (segment.size + blocks - 1) * 16
        }
    }

    fun prune(account: Long, keep: Set<Long>, current: () -> Boolean = { true }) = synchronized(diskLock) {
        requireCurrent(current)
        pruneFiles(directory(account), keep, onlyRedownloadable = false, current = current)
    }

    private fun pruneFiles(directory: File, keep: Set<Long>, onlyRedownloadable: Boolean, current: () -> Boolean) {
        for (file in files(directory, current)) {
            if (traceId(file.name) in keep || (onlyRedownloadable && !file.name.endsWith(REDOWNLOADABLE_SUFFIX))) continue
            deleteGeometry(file, current)
        }
    }

    private class DiskInventory {
        val files = mutableMapOf<File, Long>()
        var bytes = 0L
            private set

        fun put(file: File, size: Long) {
            bytes += size - (files.put(file, size) ?: 0L)
        }

        fun remove(file: File) {
            bytes -= files.remove(file) ?: 0L
        }
    }

    private companion object {
        // Instances share a filesystem lock and invalidate stale quota ledgers on writes.
        val diskLock = Any()
        var diskRevision = 0L
    }
}
