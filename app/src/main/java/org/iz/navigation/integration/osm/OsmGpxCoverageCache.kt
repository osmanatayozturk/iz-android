package org.iz.navigation.integration.osm

import java.io.DataInput
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.iz.navigation.integration.OsmDirectedPoint

internal const val MAX_OSM_TRACES = 10_000
internal const val MAX_OSM_POINTS = 300_000
private const val BLOCK_POINTS = 256
private const val RADIUS = 6_371_000.0

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
internal class OsmGpxCoverageCache(private val root: File) {
    private val verifiedFiles = ConcurrentHashMap<File, Pair<Long, Long>>()

    private fun checksum(file: RandomAccessFile, length: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var remaining = length
        file.seek(0)
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            file.readFully(buffer, 0, count)
            digest.update(buffer, 0, count)
            remaining -= count
        }
        return digest.digest()
    }

    private fun path(account: Long, id: Long): File {
        require(account > 0 && id > 0)
        return File(File(root, account.toString()), "$id.geom")
    }

    private fun header(file: RandomAccessFile, id: Long): OsmCachedTrace {
        require(file.readInt() == 3)
        require(file.readLong() == file.length())
        val sha = file.readUTF().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        return OsmCachedTrace(id, sha, OsmCoverageBounds.read(file))
    }

    fun inspect(account: Long, id: Long): OsmCachedTrace? {
        val file = path(account, id)
        if (Files.notExists(file.toPath())) return null
        return try { RandomAccessFile(file, "r").use { input ->
            val stamp = file.lastModified() to input.length()
            require(stamp.second in 128L..(MAX_OSM_POINTS * 80L + 256L))
            if (verifiedFiles[file] != stamp) {
                val actual = checksum(input, input.length() - 32)
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

    fun nearby(account: Long, trace: OsmCachedTrace, areas: List<OsmCoverageBounds>, accept: (List<OsmDirectedPoint>) -> Unit) {
        val relevant = areas.filter(trace.bounds::overlaps)
        if (relevant.isEmpty()) return
        RandomAccessFile(path(account, trace.id), "r").use { file ->
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

    fun write(account: Long, id: Long, data: OsmGpxRemoteData): OsmCachedTrace {
        val file = path(account, id)
        val dir = checkNotNull(file.parentFile)
        check(dir.isDirectory || dir.mkdirs())
        require(data.sha256.matches(Regex("[a-f0-9]{64}")))
        require(data.segments.isNotEmpty() && data.segments.none { it.isEmpty() })
        val points = data.segments.sumOf { it.size.toLong() }
        require(data.segments.size <= MAX_OSM_POINTS && points <= MAX_OSM_POINTS)
        val estimate = 512L + points * 16 + (data.segments.size + points / (BLOCK_POINTS - 1)) * 70
        if (dir.usableSpace < estimate + 32L * 1024 * 1024) throw java.io.IOException("geometry-space")
        val bounds = OsmCoverageBounds()
        for (segment in data.segments) {
            var previous: OsmDirectedPoint? = null
            for (p in segment) {
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
        val temp = File.createTempFile("geometry-", ".tmp", dir)
        try {
            FileOutputStream(temp).use { stream ->
                val out = DataOutputStream(stream.buffered())
                out.writeInt(3)
                out.writeLong(0) // filled after all blocks are written
                out.writeUTF(data.sha256)
                bounds.write(out)
                for (segment in data.segments) {
                    var start = 0
                    while (start < segment.size) {
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
                out.flush()
            }
            RandomAccessFile(temp, "rw").use { output ->
                val length = output.length()
                output.seek(4)
                output.writeLong(length + 32)
                output.write(checksum(output, length))
                output.fd.sync()
            }
            verifiedFiles.remove(file)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        return OsmCachedTrace(id, data.sha256, bounds)
    }

    fun prune(account: Long, keep: Set<Long>) {
        val directory = File(root, account.toString())
        if (Files.notExists(directory.toPath())) return
        Files.newDirectoryStream(directory.toPath(), "*.geom").use { files ->
            for (path in files) {
                val id = path.fileName.toString().removeSuffix(".geom").toLongOrNull()
                if (id !in keep) {
                    Files.deleteIfExists(path)
                    verifiedFiles.remove(path.toFile())
                }
            }
        }
    }
}
