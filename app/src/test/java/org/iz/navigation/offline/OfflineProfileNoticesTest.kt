package org.iz.navigation.offline

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineProfileNoticesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val font = "f".repeat(64)
    private val text = "Fixture full original copyright\nFixture full license text\n"
    private fun fixture(profile: String = OfflineProfileNotices.PROFILE_ID): Pair<File, String> {
        val root = temporary.newFolder()
        File(root, "notices").mkdir()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val hash = OfflineStyleAudit.sha256(bytes)
        val path = "notices/" + hash + ".txt"
        File(root, path).writeBytes(bytes)
        val manifest = JSONObject().put("schema", "iz.offline.notices.v1").put("profileId", profile)
            .put("fontBundleSha256", font).put("files", JSONArray().put(JSONObject()
                .put("path", path).put("bytes", bytes.size).put("sha256", hash))).toString()
        val digest = OfflineStyleAudit.sha256(manifest.toByteArray(Charsets.UTF_8))
        File(root, "notices/" + digest + ".json").writeText(manifest, Charsets.UTF_8)
        val style = JSONObject().put("version", 8).put("metadata", JSONObject()
            .put(OfflineProfileNotices.METADATA_KEY, JSONObject().put("id", profile)
                .put("fontBundleSha256", font).put("noticesSha256", digest))).toString()
        return root to style
    }
    private fun read(root: File, style: String) =
        OfflineProfileNotices.read(root, style, OfflineStyleAudit.sha256(style.toByteArray(Charsets.UTF_8)))

    @Test fun actualProfileNoticeTextIsReadableWithoutNetworkOrSdk() {
        val (root, style) = fixture()
        assertEquals(text, read(root, style))
    }

    @Test fun wrongStyleHashAndWrongProfileAreUnavailable() {
        val (root, style) = fixture()
        assertNull(OfflineProfileNotices.read(root, style, "0".repeat(64)))
        val (otherRoot, otherStyle) = fixture("unreviewed-style")
        assertNull(read(otherRoot, otherStyle))
    }

    @Test fun tamperedOrMissingNoticeAndManifestNeverReturnGenericClaims() {
        val (root, style) = fixture()
        assertEquals(text, read(root, style))
        val files = requireNotNull(File(root, "notices").listFiles())
        val notice = files.single { it.extension == "txt" }
        notice.appendText("tampered")
        assertNull(read(root, style))
        notice.delete()
        assertNull(read(root, style))
        files.single { it.extension == "json" }.writeText("{}")
        assertNull(read(root, style))
    }

    @Test fun retiringTheExactLocalCorpusMakesItsNoticesUnavailable() {
        val (root, style) = fixture()
        assertEquals(text, read(root, style))
        File(root, "notices").listFiles()!!.forEach { it.delete() }
        assertNull(read(root, style))
    }
}
