package org.iz.navigation.offline

import org.junit.Assert.*
import org.junit.Test

/** R3 source-only gates. No SDK initialization, network, font or tile acquisition. */
class OfflineResourceClosureReviewTest {
    private fun style(sourceUrl: String = "https://tiles.openfreemap.org/planet",
        glyphs: String = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
        sprite: String = "https://tiles.openfreemap.org/sprites/ofm_f384/ofm") =
        """{"version":8,"sources":{"openmaptiles":{"type":"vector","url":"$sourceUrl"}},"sprite":"$sprite","glyphs":"$glyphs","layers":[{"id":"road","type":"line","source":"openmaptiles","source-layer":"transportation"}]}"""
    private fun metadata(template: String = "https://tiles.openfreemap.org/planet/fixture/{z}/{x}/{y}.pbf") =
        """{"tilejson":"3.0.0","tiles":["$template"],"minzoom":0,"maxzoom":14,"scheme":"xyz"}"""

    @Test fun hostedFontAndArtworkOriginNeverProvesLicenseCompletion() {
        val result = OfflineStyleAudit.resolve(style().toByteArray()) { metadata().toByteArray() }
        assertFalse("An allowed origin is not provenance or complete copyright notices", result.closure.complete)
    }

    @Test fun arbitrarySameOriginMetadataIsRejectedBeforeFetching() {
        var fetched = 0
        assertThrows(IllegalArgumentException::class.java) {
            OfflineStyleAudit.resolve(style(sourceUrl = "https://tiles.openfreemap.org/planet/arbitrary").toByteArray()) {
                fetched++; metadata().toByteArray()
            }
        }
        assertEquals(0, fetched)
    }

    @Test fun tileTemplatesCannotSelectAnotherResourceFamilyOrRepeatCoordinateTokens() {
        for (template in listOf(
            "https://tiles.openfreemap.org/styles/{z}/{x}/{y}.pbf",
            "https://tiles.openfreemap.org/planet/fixture/{z}/{x}/{y}/{x}.pbf",
        )) assertThrows(IllegalArgumentException::class.java) {
            OfflineStyleAudit.resolve(style().toByteArray()) { metadata(template).toByteArray() }
        }
    }

    @Test fun unreviewedAtlasCannotBeAcceptedAsKnownMakiArtwork() {
        assertThrows(IllegalArgumentException::class.java) {
            OfflineStyleAudit.resolve(style(sprite = "https://tiles.openfreemap.org/sprites/unknown/ofm").toByteArray()) {
                metadata().toByteArray()
            }
        }
    }

    @Test fun encodedSeparatorsAndTraversalsCannotEnterTheResourceGraph() {
        for (url in listOf("https://tiles.openfreemap.org/planet/fixture%2fother/{z}/{x}/{y}.pbf",
            "https://tiles.openfreemap.org/planet/fixture%5cother/{z}/{x}/{y}.pbf",
            "https://tiles.openfreemap.org/planet/fixture/%252e%252e/{z}/{x}/{y}.pbf"))
            assertThrows(IllegalArgumentException::class.java) {
                OfflineStyleAudit.resolve(style().toByteArray()) { metadata(url).toByteArray() }
            }
    }
}
