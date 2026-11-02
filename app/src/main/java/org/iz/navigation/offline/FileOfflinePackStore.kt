package org.iz.navigation.offline

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One app-owned manifest. No Java object deserialization, external paths or automatic native commands. */
class FileOfflinePackStore(directory: File) : OfflinePackStore {
    private val directory = directory.canonicalFile
    private val file get() = File(directory, "pack.manifest")
    private val temporary get() = File(directory, "pack.manifest.tmp")

    override fun read(): OfflinePack? = synchronized(lock) {
        try {
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() !in 44..512_000) return@synchronized null
            DataInputStream(FileInputStream(file)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == 1)
                val count = input.readInt()
                require(count in 1..511_956)
                val payload = ByteArray(count); input.readFully(payload)
                val digest = ByteArray(32); input.readFully(digest)
                require(input.read() == -1 && MessageDigest.isEqual(digest, sha(payload)))
                decode(payload)
            }
        } catch (_: Exception) { null } // Preserve invalid evidence; caller sees unavailable, never READY.
    }

    override fun write(pack: OfflinePack) = synchronized(lock) {
        val normalized = normalize(pack)
        val payload = encode(normalized)
        require(payload.size <= 511_956)
        check(directory.isDirectory || directory.mkdirs()) { "Manifest directory unavailable" }
        require(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(temporary.toPath()))
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(MAGIC); output.writeInt(1); output.writeInt(payload.size)
                output.write(payload); output.write(sha(payload)); output.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
    override fun remove() = synchronized(lock) {
        require(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(temporary.toPath()))
        Files.deleteIfExists(file.toPath())
        Files.deleteIfExists(temporary.toPath())
        Unit
    }
    private fun normalize(pack: OfflinePack): OfflinePack {
        require(pack.downloadedBytes in 0..MAX_OFFLINE_BYTES && pack.generation >= 0 &&
            (pack.regionId == null || pack.regionId >= 0) && (pack.error == null || pack.error.length <= 500))
        val canonical = corridorPlan(pack.request)
        require(pack.rings.isEmpty() || pack.rings == canonical.rings)
        require(pack.tileCount == 0 || pack.tileCount == canonical.tileCount)
        pack.closure?.let { value ->
            require(value.styleSha256.matches(Regex("[0-9a-fA-F]{64}")))
            require(value.origins.size <= 16 && value.notices.size <= 32)
            require((value.origins + value.notices).all { it.length in 1..2000 })
        }
        if (pack.status == OfflinePackStatus.READY) require(validClosure(pack.closure) && pack.regionId != null)
        return pack.copy(request = canonical.request, rings = canonical.rings, tileCount = canonical.tileCount)
    }
    private fun encode(pack: OfflinePack): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            val request = pack.request
            out.writeUTF(request.routeId); out.writeUTF(request.sourceFingerprint); out.writeUTF(request.styleUrl)
            out.writeDouble(request.widthMeters); out.writeInt(request.minZoom); out.writeInt(request.maxZoom)
            out.writeInt(request.line.size)
            request.line.forEach { out.writeDouble(it.latitude); out.writeDouble(it.longitude) }
            out.writeUTF(pack.status.name); out.writeLong(pack.downloadedBytes)
            out.writeBoolean(pack.regionId != null); pack.regionId?.let(out::writeLong)
            out.writeLong(pack.generation)
            out.writeBoolean(pack.error != null); pack.error?.let(out::writeUTF)
            out.writeBoolean(pack.closure != null)
            pack.closure?.let { value ->
                out.writeUTF(value.styleSha256); out.writeBoolean(value.complete)
                for (set in listOf(value.origins, value.notices)) {
                    out.writeInt(set.size); set.sorted().forEach(out::writeUTF)
                }
            }
        }
        return buffer.toByteArray()
    }
    private fun decode(payload: ByteArray): OfflinePack = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        fun text(limit: Int) = input.readUTF().also { require(it.length <= limit) }
        val route = text(300); val fingerprint = text(64); val style = text(2000)
        val width = input.readDouble(); val minZoom = input.readInt(); val maxZoom = input.readInt()
        val count = input.readInt(); require(count in 2..512)
        val points = List(count) { OfflinePoint(input.readDouble(), input.readDouble()) }
        val status = OfflinePackStatus.valueOf(text(30)); val bytes = input.readLong()
        val region = if (input.readBoolean()) input.readLong() else null
        val generation = input.readLong()
        val error = if (input.readBoolean()) text(500) else null
        val closure = if (input.readBoolean()) {
            val digest = text(64); val complete = input.readBoolean()
            fun strings(max: Int): Set<String> {
                val size = input.readInt(); require(size in 0..max)
                val values = List(size) { text(2000) }
                require(values.toSet().size == size)
                return values.toSet()
            }
            OfflineResourceClosure(digest, strings(16), strings(32), complete)
        } else null
        require(input.read() == -1)
        normalize(OfflinePack(OfflineCorridorRequest(route, fingerprint, points, width, minZoom, maxZoom, style),
            status, downloadedBytes = bytes, regionId = region, generation = generation, closure = closure, error = error))
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    companion object {
        private const val MAGIC = 0x495A4F46
        private val lock = Any()
    }
}
