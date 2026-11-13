package org.iz.navigation.data

import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.iz.navigation.integration.osm.OsmCredentials
import org.iz.navigation.integration.osm.OsmUser
import org.iz.navigation.integration.osm.upgradeEditorDraft
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.iz.navigation.osmcatalog.OsmPresetRuleSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OsmLightEditorBackupTest {
    @Test fun everyNameAndPhoneIntentAndBothExactRuleSnapshotsSurviveVersionTwelve() {
        val before = mapOf(
            "amenity" to "cafe", "name" to "Original name", "phone" to "111", "contact:phone" to "222",
            "addr:street" to "Keep street", "addr:housenumber" to "12A", "building" to "yes", "operator" to "Keep operator",
        )
        for (nameAction in MapEditFieldAction.entries) for (phoneAction in MapEditFieldAction.entries) {
            val draft = correction().copy(
                nameAction = nameAction,
                name = when (nameAction) {
                    MapEditFieldAction.UNTOUCHED -> "Original name"
                    MapEditFieldAction.SET -> "Changed name"
                    MapEditFieldAction.DELETE -> ""
                },
                phoneAction = phoneAction,
                phone = when (phoneAction) {
                    MapEditFieldAction.UNTOUCHED -> "111; 222"
                    MapEditFieldAction.SET -> "+90 312 000 00 00"
                    MapEditFieldAction.DELETE -> ""
                },
            )
            val json = BackupJson.encode(DiarySnapshot(mapEdits = listOf(draft)))
            assertEquals(12, json.getInt("version"))
            // Parse serialized JSON too: retaining the original JSONObject is not a portable round trip.
            val restored = BackupJson.decode(JSONObject(json.toString())).mapEdits.single()
            val label = "name=$nameAction phone=$phoneAction"
            assertEquals(label, draft, restored)
            assertEquals(label, draft.presetSnapshot, restored.presetSnapshot)
            assertEquals(label, draft.originalPresetSnapshot, restored.originalPresetSnapshot)

            val expected = mutableMapOf(
                "amenity" to "pharmacy", "healthcare" to "pharmacy", "addr:street" to "Keep street",
                "addr:housenumber" to "12A", "building" to "yes", "operator" to "Keep operator",
            )
            when (nameAction) {
                MapEditFieldAction.UNTOUCHED -> expected["name"] = "Original name"
                MapEditFieldAction.SET -> expected["name"] = "Changed name"
                MapEditFieldAction.DELETE -> Unit
            }
            when (phoneAction) {
                MapEditFieldAction.UNTOUCHED -> { expected["phone"] = "111"; expected["contact:phone"] = "222" }
                MapEditFieldAction.SET -> expected["phone"] = "+90 312 000 00 00"
                MapEditFieldAction.DELETE -> Unit
            }
            // Golden maps do not come from the tag-transition implementation.
            assertEquals(label, expected, restored.editorTags(before))
        }
    }

    @Test fun unselectedVersionTwoDraftDoesNotAcquireTheLegacyDefaultTypeOnRestore() {
        val draft = newMapEditDraft(39.93, 32.85).copy(observedAt = 1_000)
        val restored = BackupJson.decode(JSONObject(BackupJson.encode(DiarySnapshot(mapEdits = listOf(draft))).toString()))
            .mapEdits.single()
        assertEquals(draft, restored)
        assertEquals(2, restored.editorVersion)
        assertNull(restored.presetId)
        assertNull(restored.presetSnapshot)
        assertNull(restored.originalPresetSnapshot)
        assertEquals(MapEditFieldAction.UNTOUCHED, restored.nameAction)
        assertEquals(MapEditFieldAction.UNTOUCHED, restored.phoneAction)
        validateMapEdit(restored)
        assertThrows(IllegalArgumentException::class.java) { validateMapEditForPublish(restored.copy(surveyConfirmed = true)) }
    }

    @Test fun independentlyWrittenLegacyArchivesKeepEnumAddressAndVersionOneSemantics() {
        val expected = MapEditDraft(
            id = "10000000-0000-4000-8000-000000000001", latitude = 39.93, longitude = 32.85,
            preset = MapPlacePreset.DRINKING_WATER, name = "Legacy fountain", street = "Keep old street",
            houseNumber = "12A", observedAt = 1_000,
        )
        for (version in listOf(5, 9, 10, 11)) {
            // This fixture is not produced by downgrading the new encoder's output.
            val legacy = JSONObject(LEGACY_BACKUP).put("version", version)
            val restored = BackupJson.decode(legacy)
            assertEquals("legacy version $version", expected, restored.mapEdits.single())
            assertTrue(restored.osmPresetFavorites.isEmpty())
            assertEquals(
                mapOf("amenity" to "drinking_water", "name" to "Legacy fountain", "addr:street" to "Keep old street", "addr:housenumber" to "12A"),
                restored.mapEdits.single().publicTags(),
            )
            val reexported = BackupJson.encode(restored)
            assertEquals(12, reexported.getInt("version"))
            assertEquals(expected, BackupJson.decode(JSONObject(reexported.toString())).mapEdits.single())
        }
    }

    @Test fun legacyAddressOnlyCorrectionKeepsItsIntentThroughBackupAndExplicitReviewedUpgrade() {
        val json = JSONObject(LEGACY_BACKUP)
        json.getJSONArray("mapEdits").getJSONObject(0)
            .put("preset", "CAFE").put("name", "").put("targetType", "NODE").put("targetId", 77)
            .put("surveyConfirmed", true)
        val legacy = MapEditDraft(
            id = "10000000-0000-4000-8000-000000000001", latitude = 39.93, longitude = 32.85,
            street = "Keep old street", houseNumber = "12A", observedAt = 1_000,
            targetType = OsmType.NODE, targetId = 77, surveyConfirmed = true,
        )
        val restoredLegacy = BackupJson.decode(json).mapEdits.single()
        assertEquals(legacy, restoredLegacy)
        val addressChanges = mapOf("addr:street" to "Keep old street", "addr:housenumber" to "12A")
        assertEquals(addressChanges, restoredLegacy.publicTags())
        assertEquals(legacy, roundTrip(restoredLegacy))

        val before = mapOf(
            "amenity" to "cafe", "name" to "Keep name", "phone" to "111", "contact:phone" to "222",
            "addr:street" to "Previous street", "addr:housenumber" to "1", "operator" to "Keep operator",
        )
        val element = OsmCorrectionElement(OsmRef(OsmType.NODE, 77), 3, 5, 41, "39.93", "32.85", emptyList(), before)
        val upgraded = element.upgradeEditorDraft(restoredLegacy, catalog())
        assertTrue(upgraded.legacyAddressIntent)
        assertEquals(MapEditFieldAction.UNTOUCHED, upgraded.nameAction)
        assertEquals(MapEditFieldAction.UNTOUCHED, upgraded.phoneAction)
        val reopened = roundTrip(upgraded)
        assertEquals(upgraded, reopened)
        assertEquals(addressChanges, reopened.publicTags())
        val account = OsmCredentials("fictional-token", OsmUser(41, "Mapper"), null, setOf("write_api"))
        val review = element.review(reopened, account)
        assertEquals(before + addressChanges, review.corrected.tags)
        assertEquals(
            mapOf("addr:street" to ("Previous street" to "Keep old street"), "addr:housenumber" to ("1" to "12A")),
            review.fields.associate { it.key to (it.before to it.after) },
        )
    }

    @Test fun legacyNewNodeKeepsAddressIntentUntilItIsExplicitlyUpgradedAndReviewed() {
        val legacy = MapEditDraft(
            latitude = 39.93, longitude = 32.85, name = "Legacy cafe", street = "Keep old street",
            houseNumber = "12A", observedAt = 1_000,
        )
        val restoredLegacy = roundTrip(legacy)
        assertEquals(legacy, restoredLegacy)
        assertEquals(1, restoredLegacy.editorVersion)
        val expected = mapOf("amenity" to "cafe", "name" to "Legacy cafe", "addr:street" to "Keep old street", "addr:housenumber" to "12A")
        assertEquals(expected, restoredLegacy.publicTags())
        val upgraded = restoredLegacy.upgradeEditorDraft(catalog())
        assertEquals(2, upgraded.editorVersion)
        assertTrue(upgraded.legacyAddressIntent)
        val restoredUpgrade = roundTrip(upgraded)
        assertEquals(upgraded, restoredUpgrade)
        assertEquals(expected, restoredUpgrade.publicTags())
    }

    @Test fun newVersionTwoBackupNeverTurnsHiddenAddressColumnsIntoPublicationIntent() {
        val draft = newMapEditDraft(39.93, 32.85).selectPreset(catalog().snapshot("amenity/cafe", OsmPresetGeometry.POINT))
            .copy(observedAt = 1_000, street = "Hidden old column", houseNumber = "99")
        val restored = roundTrip(draft)
        assertEquals(draft, restored)
        assertFalse(restored.legacyAddressIntent)
        assertEquals("Hidden old column", restored.street)
        assertEquals("99", restored.houseNumber)
        assertEquals(mapOf("amenity" to "cafe"), restored.publicTags())
    }

    @Test fun unavailableFavoriteIdsRoundTripInStableSortedUniqueOrderWithoutCatalogRemapping() {
        val input = listOf("unavailable/zeta", "amenity/cafe", "unavailable/alpha").map(::OsmPresetFavorite)
        val expectedIds = listOf("amenity/cafe", "unavailable/alpha", "unavailable/zeta")
        var restored = DiarySnapshot(osmPresetFavorites = input)
        repeat(2) {
            val json = BackupJson.encode(restored)
            assertEquals(12, json.getInt("version"))
            restored = BackupJson.decode(JSONObject(json.toString()))
            val ids = restored.osmPresetFavorites.map { it.presetId }
            assertEquals(expectedIds, ids)
            assertEquals(ids.size, ids.toSet().size)
            assertTrue(restored.mapEdits.isEmpty()) // Favorites never become a public edit.
        }
    }

    @Test fun uncertainVersionTwoWriteRetainsPhoneAndFrozenTypeWithoutPortableAttemptAuthority() {
        val snapshot = rule("fixture/unavailable-pharmacy", "pharmacy", OsmPresetGeometry.POINT)
        val sending = MapEditDraft(
            latitude = 39.93, longitude = 32.85, observedAt = 100, editorVersion = 2,
            presetId = "fixture/unavailable-pharmacy", presetSnapshot = snapshot,
            phone = "+90 312 000 00 00", phoneAction = MapEditFieldAction.SET,
            surveyConfirmed = true, status = MapEditStatus.SENDING, stage = MapEditStage.UPLOAD_NODE,
            submittedAt = 200, submittedBy = 5, changesetId = 7,
            localAttemptId = "installation-only-attempt", localChangesetCreated = true,
        )
        val json = BackupJson.encode(DiarySnapshot(mapEdits = listOf(sending)))
        val record = json.getJSONArray("mapEdits").getJSONObject(0)
        assertFalse(record.has("localAttemptId"))
        assertFalse(record.has("localChangesetCreated"))
        val restored = BackupJson.decode(JSONObject(json.toString())).mapEdits.single()
        assertEquals(sending.copy(status = MapEditStatus.UNKNOWN, localAttemptId = null, localChangesetCreated = false), restored)
        assertEquals(mapOf("amenity" to "pharmacy", "healthcare" to "pharmacy", "phone" to "+90 312 000 00 00"), restored.publicTags())
    }

    private fun correction() = MapEditDraft(
        latitude = 39.93, longitude = 32.85, observedAt = 1_000, targetType = OsmType.WAY, targetId = 77,
        editorVersion = 2, presetId = "fixture/unavailable-pharmacy",
        presetSnapshot = rule("fixture/unavailable-pharmacy", "pharmacy", OsmPresetGeometry.AREA),
        originalPresetSnapshot = rule("fixture/old-cafe", "cafe", OsmPresetGeometry.AREA),
        typeChanged = true, street = "Legacy hidden street", houseNumber = "Legacy hidden number",
    )

    private fun roundTrip(draft: MapEditDraft): MapEditDraft =
        BackupJson.decode(JSONObject(BackupJson.encode(DiarySnapshot(mapEdits = listOf(draft))).toString())).mapEdits.single()

    private fun catalog(): OsmPresetCatalog {
        val ruleJson = OsmPresetRuleSnapshot.fromJson(rule("amenity/cafe", "cafe", OsmPresetGeometry.POINT)).ruleJson
        return OsmPresetCatalog.fromJson(JSONObject()
            .put("format", "iz.osm-light-catalogue").put("schemaVersion", 1)
            .put("upstream", JSONObject().put("commit", "a".repeat(40)))
            .put("presets", JSONArray().put(JSONObject(ruleJson))).toString())
    }

    private fun rule(id: String, amenity: String, geometry: OsmPresetGeometry): String {
        val tags = if (amenity == "pharmacy") "\"amenity\":\"pharmacy\",\"healthcare\":\"pharmacy\"" else "\"amenity\":\"cafe\""
        val rule = """{
            "id":"$id","name":"Fixture $amenity","sourceName":"Fixture $amenity","trName":null,
            "geometry":["point","area"],"sourceGeometry":["point","area"],
            "tags":{$tags},"addTags":{$tags},"removeTags":{$tags},
            "aliases":{"tr":[],"en":[]},"terms":{"tr":[],"en":[]},
            "searchable":true,"selectable":true,"matchScore":1.0,"locationSet":null,
            "requiresLocationValidation":false,"normalizedSearch":"fixture $amenity"
        }""".trimIndent()
        // Whitespace makes accidental parse/re-encode of the persisted opaque string observable.
        return " \n" + OsmPresetRuleSnapshot("a".repeat(40), "b".repeat(64), id, geometry, rule).toJson() + "\n "
    }

    private companion object {
        val LEGACY_BACKUP = """{
            "format":"org.iz.navigation.backup","version":11,"exportedAt":2000,
            "journeys":[],"points":[],"places":[],"visits":[],"photos":[],"drafts":[],
            "contributions":[],"healthSamples":[],"watchHealthSessions":[],"watchHealthSamples":[],
            "savedPlans":[],"importedTracks":[],"collections":[],"memberships":[],"destinationShortcuts":[],"leanSessions":[],
            "mapEdits":[{
                "id":"10000000-0000-4000-8000-000000000001","placeId":null,"latitude":39.93,"longitude":32.85,
                "preset":"DRINKING_WATER","name":"Legacy fountain","street":"Keep old street","houseNumber":"12A",
                "observedAt":1000,"surveyConfirmed":false,"status":"DRAFT","stage":"NONE",
                "submittedAt":null,"submittedBy":null,"changesetId":null,"remoteNodeId":null,"remoteNodeVersion":null,
                "changesetClosed":false,"error":null,"targetType":null,"targetId":null,"reviewedVersion":null,
                "reviewedFingerprint":null,"appliedVersion":null,"surveyPayload":null
            }]
        }""".trimIndent()
    }
}
