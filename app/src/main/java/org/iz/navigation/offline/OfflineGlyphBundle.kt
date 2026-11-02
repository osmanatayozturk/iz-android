package org.iz.navigation.offline

import org.json.JSONObject

/** Exact installed generated asset bundle, independently sealed by the assigned producer. */
class OfflineGlyphBundle private constructor(val sha256: String, val files: Map<String, ByteArray>,
    val glyphPaths: Set<String>) {
    val name: String get() = NAME
    companion object {
        const val NAME = "Iz Offline Latin Regular"
        fun verify(manifest: ByteArray, expectedSha256: String, read: (String) -> ByteArray): OfflineGlyphBundle {
            require(manifest.size in 1..128_000 && OfflineStyleAudit.sha256(manifest) == OfflineProfileNotices.digest(expectedSha256))
            val root = JSONObject(manifest.toString(Charsets.UTF_8))
            require(root.getString("schema") == "iz.offline-font.bundle.v1" &&
                root.getString("name") == NAME && root.getString("fontstack") == NAME &&
                root.getInt("rangeCount") == 256 && root.getInt("rangeSize") == 256 &&
                root.getInt("first") == 0 && root.getInt("last") == 65535 &&
                root.getString("contentEncoding") == "identity" && root.getString("license") == "OFL-1.1" &&
                !root.getBoolean("generatorIncluded"))
            val rows = root.getJSONArray("files"); require(rows.length() == 260)
            val glyphs = (0 until 256).map { "glyphs/" + NAME + "/" + (it * 256) + "-" + (it * 256 + 255) + ".pbf" }.toSet()
            val extras = setOf("notices/fonts/carlito/OFL.txt", "notices/fonts/carlito/AUTHORS.txt",
                "notices/fonts/IZ-OFFLINE-LATIN-PROVENANCE.json", "GLYPH-INVENTORY.json")
            val verified = linkedMapOf<String, ByteArray>(); var total = 0L
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index); val path = row.getString("path")
                require(path in glyphs + extras && path !in verified)
                val size = row.getInt("bytes"); require(size in 1..1_000_000)
                if (path in glyphs) require(size <= 512_000)
                val bytes = read(path); require(bytes.size == size && OfflineStyleAudit.sha256(bytes).equals(row.getString("sha256"), true))
                total += bytes.size; require(total <= 10_000_000)
                verified[path] = bytes.copyOf()
            }
            require(verified.keys == glyphs + extras)
            require(OfflineStyleAudit.sha256(requireNotNull(verified["notices/fonts/carlito/OFL.txt"])) ==
                "58402f82a7c332a700294988fe7554fbb0a63a8d27ccc1ee3bbc640311990a00")
            require(OfflineStyleAudit.sha256(requireNotNull(verified["notices/fonts/carlito/AUTHORS.txt"])) ==
                "127a5f8f8e10214b4cce882d921c24d183656fd2220d7ad4185136f3a0eadfd3")
            return OfflineGlyphBundle(expectedSha256, verified.toMap(), glyphs)
        }
    }
}
