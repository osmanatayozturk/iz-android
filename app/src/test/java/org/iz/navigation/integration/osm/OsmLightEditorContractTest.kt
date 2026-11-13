package org.iz.navigation.integration.osm

import java.io.File
import org.iz.navigation.data.*
import org.iz.navigation.osmcatalog.*
import org.junit.Assert.*
import org.junit.Test

class OsmLightEditorContractTest {
    private val catalog by lazy {
        val file = System.getProperty("iz.catalogue")?.let(::File)
            ?: File("src/main/assets/osm-light-catalogue-v1.json").takeIf { it.isFile }
            ?: File("app/src/main/assets/osm-light-catalogue-v1.json")
        OsmPresetCatalog.load(file.inputStream())
    }
    private val account = OsmCredentials("fictional-token", OsmUser(41, "Mapper"), null, setOf("write_api"))
    private fun draft(id: String? = null, geometry: OsmPresetGeometry = OsmPresetGeometry.POINT) = MapEditDraft(
        latitude = 41.0, longitude = 29.0, editorVersion = 2, surveyConfirmed = true,
        presetId = id, presetSnapshot = id?.let { catalog.snapshot(it, geometry).toJson() },
    )
    private fun original(tags: Map<String, String>, way: Boolean = false) = OsmCorrectionElement(
        OsmRef(if (way) OsmType.WAY else OsmType.NODE, 77), 3, 5, 41,
        if (way) null else "41", if (way) null else "29", if (way) listOf(1, 2, 3, 1) else emptyList(), tags,
    )
    private fun correction(element: OsmCorrectionElement, selected: String, previous: String? = selected): MapEditDraft {
        val geometry = if (element.ref.type == OsmType.WAY) OsmPresetGeometry.AREA else OsmPresetGeometry.POINT
        return draft(selected, geometry).copy(targetType = element.ref.type, targetId = element.ref.id,
            originalPresetSnapshot = previous?.let { catalog.snapshot(it, geometry).toJson() })
    }

    @Test fun blankNewTypeCanBeSavedButCannotPublishAsAnImplicitCafe() {
        val empty = draft()
        validateMapEdit(empty)
        assertThrows(IllegalArgumentException::class.java) { validateMapEditForPublish(empty) }
    }

    @Test fun newPointPublishesFrozenTypeNameAndPhoneWithoutHiddenLegacyAddress() {
        val value = draft("amenity/pharmacy").copy(name = "Eczane", nameAction = MapEditFieldAction.SET,
            phone = "+90 312 123 45 67", phoneAction = MapEditFieldAction.SET, street = "Legacy", houseNumber = "9")
        validateMapEditForPublish(value)
        assertEquals(mapOf("amenity" to "pharmacy", "healthcare" to "pharmacy", "name" to "Eczane", "phone" to "+90 312 123 45 67"), value.publicTags())
        assertEquals("Legacy", value.street)
    }

    @Test fun explicitNameAndPhoneDeletionRemovesBothAliasesButKeepsAddressBuildingAndGeometry() {
        val element = original(mapOf("amenity" to "cafe", "name" to "Old", "phone" to "111",
            "contact:phone" to "222", "addr:street" to "Preserve", "building" to "yes"), way = true)
        val value = correction(element, "amenity/cafe").copy(nameAction = MapEditFieldAction.DELETE,
            phoneAction = MapEditFieldAction.DELETE)
        val review = element.review(value, account)
        assertEquals(mapOf("amenity" to "cafe", "addr:street" to "Preserve", "building" to "yes"), review.corrected.tags)
        assertEquals(element.nodeRefs, review.corrected.nodeRefs)
        assertEquals(setOf("name", "phone", "contact:phone"), review.fields.map { it.key }.toSet())
    }

    @Test fun phoneSetMakesAliasConsolidationVisibleAndPreservesUntouchedName() {
        val element = original(mapOf("amenity" to "cafe", "name" to "Old", "phone" to "111", "contact:phone" to "222", "operator" to "Keep"))
        val value = correction(element, "amenity/cafe").copy(phone = "333", phoneAction = MapEditFieldAction.SET)
        val review = element.review(value, account)
        assertEquals(mapOf("amenity" to "cafe", "name" to "Old", "phone" to "333", "operator" to "Keep"), review.corrected.tags)
        assertEquals(setOf("phone", "contact:phone"), review.fields.map { it.key }.toSet())
    }

    @Test fun typeChangeUsesFrozenRecipesWithoutMovingWayOrChangingContactAndAddress() {
        val element = original(mapOf("amenity" to "cafe", "name" to "Old", "phone" to "111", "addr:housenumber" to "7", "building" to "yes"), way = true)
        val value = correction(element, "amenity/restaurant", "amenity/cafe").copy(typeChanged = true)
        val review = element.review(value, account)
        assertEquals(mapOf("amenity" to "restaurant", "name" to "Old", "phone" to "111", "addr:housenumber" to "7", "building" to "yes"), review.corrected.tags)
        assertEquals(element.nodeRefs, review.corrected.nodeRefs)
        assertEquals(listOf("amenity"), review.fields.map { it.key })
    }

    @Test fun existingBuildingSubtypeCanChangeWithoutInventingANewOutline() {
        val element = original(mapOf("building" to "yes", "addr:street" to "Keep", "height" to "8"), way = true)
        val value = correction(element, "building/house", "building").copy(typeChanged = true)
        val review = element.review(value, account)
        assertEquals("house", review.corrected.tags["building"])
        assertEquals("Keep", review.corrected.tags["addr:street"])
        assertEquals("8", review.corrected.tags["height"])
        assertEquals(element.nodeRefs, review.corrected.nodeRefs)
    }

    @Test fun areaSnapshotCannotCreateANodeEvenWithLegacyCafeFallbackPresent() {
        val value = draft("building/house", OsmPresetGeometry.AREA)
        assertThrows(IllegalArgumentException::class.java) { validateMapEditForPublish(value) }
    }

    @Test fun phoneControlCharactersAreRejectedBeforePreviewOrUpload() {
        val value = draft("amenity/cafe").copy(phone = "123\n456", phoneAction = MapEditFieldAction.SET)
        assertThrows(IllegalArgumentException::class.java) { validateMapEdit(value) }
    }

    @Test fun legacyBlankFieldsStillPreserveTheirExistingTags() {
        val element = original(mapOf("amenity" to "cafe", "name" to "Old", "phone" to "111", "addr:street" to "Keep"))
        val legacy = MapEditDraft(latitude = 41.0, longitude = 29.0, targetType = OsmType.NODE, targetId = 77,
            houseNumber = "9", surveyConfirmed = true)
        assertEquals(mapOf("amenity" to "cafe", "name" to "Old", "phone" to "111", "addr:street" to "Keep", "addr:housenumber" to "9"), element.review(legacy, account).corrected.tags)
    }

    @Test fun correctionPublicChangeTagsDoNotTryToRemoveOldTypeFromAnEmptyMap() {
        val element = original(mapOf("amenity" to "cafe"))
        val value = correction(element, "amenity/restaurant", "amenity/cafe").copy(typeChanged = true)
        assertEquals(mapOf("amenity" to "restaurant"), value.publicTags())
    }

    @Test fun legacyAddressOnlyCorrectionUpgradeKeepsIntentAndReviewShowsAddress() {
        val element = original(mapOf("amenity" to "cafe", "name" to "Old", "addr:street" to "Before"))
        val legacy = MapEditDraft(latitude = 41.0, longitude = 29.0, targetType = OsmType.NODE, targetId = 77,
            street = "After", surveyConfirmed = true)
        val upgraded = element.upgradeEditorDraft(legacy, catalog)
        val review = element.review(upgraded, account)
        assertEquals("After", review.corrected.tags["addr:street"])
        assertEquals("Old", review.corrected.tags["name"])
        assertEquals(listOf(OsmCorrectionField("addr:street", "Before", "After")), review.fields)
    }

    @Test fun closedHighwayRoundaboutCannotAcquireAreaEditingFromClosureAlone() {
        val element = original(mapOf("highway" to "residential", "junction" to "roundabout"), way = true)
        assertThrows(OsmUnsupportedCorrection::class.java) {
            element.editorDraft(catalog, "10000000-0000-4000-8000-000000000001", 41.0, 29.0)
        }
    }

    @Test fun ambiguousClosedTrackRequiresExplicitAreaEvidence() {
        val element = original(mapOf("leisure" to "track"), way = true)
        assertThrows(OsmUnsupportedCorrection::class.java) {
            element.editorDraft(catalog, "10000000-0000-4000-8000-000000000001", 41.0, 29.0)
        }
    }

    @Test fun explicitTrackAreaAndOrdinaryAmenityAndBuildingKeepAreaEligibility() {
        for (tags in listOf(mapOf("leisure" to "track", "area" to "yes"), mapOf("amenity" to "restaurant"), mapOf("building" to "house"))) {
            val element = original(tags, way = true)
            val value = element.editorDraft(catalog, "10000000-0000-4000-8000-000000000001", 41.0, 29.0)
            assertNotNull(value.presetId)
            assertEquals(OsmPresetGeometry.AREA, value.selectedPresetSnapshot()!!.geometry)
        }
    }

    @Test fun broadCataloguePointChangesetDoesNotClaimTheLegacyCafePlaceholderWasAdded() {
        val value = draft("natural/tree")
        assertEquals(mapOf("natural" to "tree"), value.publicTags())
        val xml = OsmMapEditXml.changeset(value)
        assertTrue(xml.contains("Yerinde gözlemlenen OSM noktası eklendi"))
        assertFalse(xml.contains("kafe eklendi"))
    }
}
