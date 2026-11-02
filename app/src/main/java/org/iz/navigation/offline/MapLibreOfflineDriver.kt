package org.iz.navigation.offline

import android.content.Context
import android.os.Looper
import org.iz.navigation.integration.OsmMapRuntime
import java.io.File
import java.security.MessageDigest
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point

/**
 * Native13.6.1 adapter. Invoke on the UI thread, only after explicit download/resume.
 * resolveStyle returns an immutable, audited local style file, never the public URL directly.
 * No SDK manager is created by constructing this driver or restoring a controller.
 */
class MapLibreOfflineDriver(
    context: Context,
    private val styleDirectory: File,
    private val resolveStyle: (OfflinePack) -> File,
    private val expectedPack: () -> OfflinePack?,
) : OfflineNativeDriver {
    private val app = context.applicationContext
    private val manager by lazy {
        requireOfflineMainThread()
        OsmMapRuntime.initialize(app)
        OfflineManager.getInstance(app)
    }

    override fun create(pack: OfflinePack, created: (OfflineDriverRegion) -> Unit, failed: (String) -> Unit) {
        try {
            requireOfflineMainThread()
            require(pack.rings == corridorPlan(pack.request).rings && pack.tileCount in 1..MAX_OFFLINE_TILES)
            val style = ownedStyle(pack)
            val styleSha256 = styleDigest(style)
            val geometry = MultiPolygon.fromLngLats(pack.rings.map { ring ->
                listOf(ring.map { Point.fromLngLat(it.longitude, it.latitude) })
            })
            val profile = OfflineLabelledProfile.parse(OfflineResourceCorpus.readBounded(style, 512_000))
            val corpus = org.json.JSONObject(profile.json).getJSONObject("metadata")
                .getJSONObject(OfflineProfileNotices.METADATA_KEY).getString("corpusId")
            val definition = OfflineGeometryRegionDefinition(OfflineLocalResources.styleUrl(corpus), geometry,
                pack.request.minZoom.toDouble(), pack.request.maxZoom.toDouble(), 1f, false)
            // Metadata is a bounded structural identity; no route coordinates or credentials.
            val metadata = OfflineRegionIdentity.metadata(pack, styleSha256)
            manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) { created(Region(offlineRegion)) }
                override fun onError(error: String) { failed("Harita alanı oluşturulamadı.") }
            })
        } catch (_: Exception) { failed("Harita alanı oluşturulamadı.") }
    }

    override fun get(id: Long, found: (OfflineDriverRegion) -> Unit, failed: (String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) { failed("Harita işlemi ana iş parçacığında başlatılmalı."); return }
        if (id < 0) { failed("Harita alanı bulunamadı."); return }
        val expectedPack = expectedPack()
        if (expectedPack == null || expectedPack.regionId != id) { failed("Harita alanı bulunamadı."); return }
        val digest = try { styleDigest(ownedStyle(expectedPack)) }
            catch (_: Exception) { failed("Harita alanı doğrulanamadı."); return }
        manager.getOfflineRegion(id, object : OfflineManager.GetOfflineRegionCallback {
            override fun onRegion(offlineRegion: OfflineRegion) {
                val live = this@MapLibreOfflineDriver.expectedPack()
                if (live == null || live.regionId != id ||
                    !OfflineRegionIdentity.matches(offlineRegion.metadata, expectedPack, digest) ||
                    !OfflineRegionIdentity.matches(offlineRegion.metadata, live, digest)) {
                    failed("Harita alanı doğrulanamadı."); return
                }
                found(Region(offlineRegion))
            }
            override fun onRegionNotFound() { failed("Harita alanı bulunamadı.") }
            override fun onError(error: String) { failed("Harita alanı bulunamadı.") }
        })
    }

    private fun ownedStyle(pack: OfflinePack): File {
        val file = resolveStyle(pack).canonicalFile
        val root = styleDirectory.canonicalFile
        require(file.isFile && file.length() in 1..1_000_000 &&
            file.toPath().startsWith(root.toPath()) && file != root)
        return file
    }
    private fun styleDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= 1_000_000)
                digest.update(buffer, 0, read)
            }
        }
        require(count > 0)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private class Region(private val region: OfflineRegion) : OfflineDriverRegion {
        private var terminal = false
        override val id: Long get() = region.id
        override fun observe(observer: (OfflineDriverStatus) -> Unit, failed: (String) -> Unit) {
            requireOfflineMainThread()
            check(!terminal) { "Region deletion already started" }
            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    if (!terminal) observer(OfflineDriverStatus(status.completedResourceCount, status.requiredResourceCount,
                        status.isRequiredResourceCountPrecise))
                }
                override fun onError(error: OfflineRegionError) { if (!terminal) failed("Harita alanı indirilemedi.") }
                override fun mapboxTileCountLimitExceeded(limit: Long) { if (!terminal) failed("Harita alanı indirme sınırını aşıyor.") }
            })
        }
        override fun active() {
            requireOfflineMainThread()
            check(!terminal) { "Region deletion already started" }
            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }
        override fun inactive() {
            requireOfflineMainThread()
            if (terminal) return
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            region.setObserver(null)
        }
        override fun delete(done: () -> Unit, failed: (String) -> Unit) {
            requireOfflineMainThread()
            if (terminal) { failed("Harita alanı kaldırılıyor."); return }
            inactive()
            terminal = true
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() { done() }
                override fun onError(error: String) { terminal = false; failed("Harita alanı kaldırılamadı.") }
            })
        }
    }
}

private fun requireOfflineMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Offline native operation requires the main thread" }
}
