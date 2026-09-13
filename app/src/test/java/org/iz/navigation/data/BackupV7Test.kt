package org.iz.navigation.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupV7Test {
    @Test fun newBackupHasVersionSevenAndEmptyLibraryLists() {
        val encoded = BackupJson.encode(DiarySnapshot())
        assertEquals(7, encoded.getInt("version"))
        listOf("savedPlans", "importedTracks", "collections", "memberships").forEach {
            assertEquals(0, encoded.getJSONArray(it).length())
        }
    }

    @Test fun versionSevenRoundTripsPlansGeometryAndOrderedMemberships() {
        val journey = Journey(id = "journey", startedAt = 10, endedAt = 20)
        val json = BackupJson.encode(DiarySnapshot(journeys = listOf(journey))).put("version", 7)
        json.put("savedPlans", JSONArray("""[{"id":"plan","name":"Weekend","stops":[{"label":"A","latitude":10,"longitude":20},{"label":"B","latitude":10.1,"longitude":20.1}],"transport":"WALK","preferences":{"avoidHighways":true,"avoidTolls":false,"avoidFerries":true},"travelSpeedKmh":5,"originUsesCurrentLocation":true,"createdAt":10,"updatedAt":20}]"""))
        json.put("importedTracks", JSONArray("""[{"id":"track","name":"Park","createdAt":10,"segments":[{"name":"Section","trackName":"Trail","trackIndex":1,"points":[{"latitude":10,"longitude":20},{"latitude":10.1,"longitude":20.1}]}]}]"""))
        json.put("collections", JSONArray("""[{"id":"collection","name":"Summer","createdAt":10,"updatedAt":20}]"""))
        json.put("memberships", JSONArray("""[{"collectionId":"collection","journeyId":"journey","sortOrder":0}]"""))
        val restored = BackupJson.encode(BackupJson.decode(json))
        listOf("savedPlans", "importedTracks", "collections", "memberships").forEach {
            assertEquals("Round trip $it", canonical(json.getJSONArray(it)), canonical(restored.getJSONArray(it)))
        }
    }

    @Test fun everyOldBackupWithoutLibraryFieldsStillLoads() {
        for (version in 1..6) {
            val json = BackupJson.encode(DiarySnapshot()).put("version", version)
            listOf("savedPlans", "importedTracks", "collections", "memberships").forEach { json.remove(it) }
            val encoded = BackupJson.encode(BackupJson.decode(json))
            listOf("savedPlans", "importedTracks", "collections", "memberships").forEach {
                assertEquals("Legacy version $version / $it", 0, encoded.getJSONArray(it).length())
            }
        }
    }

    @Test fun missingVersionSevenListsDefaultToEmptyButWrongShapesAreRejected() {
        val json = BackupJson.encode(DiarySnapshot())
        listOf("savedPlans", "importedTracks", "collections", "memberships").forEach { json.remove(it) }
        assertEquals(DiarySnapshot(), BackupJson.decode(json))
        for (bad in listOf(JSONObject.NULL, JSONObject(), "[]")) {
            val malformed = JSONObject(json.toString()).put("savedPlans", bad)
            assertThrows(Exception::class.java) { BackupJson.decode(malformed) }
        }
    }

    @Test fun foreignMetadataIsDiscardedAndNotSavedInTheLibrary() {
        val json = fixture()
        json.getJSONArray("savedPlans").getJSONObject(0).put("apiKey", "DROP_PROVIDER_SECRET").put("eta", "DROP_ETA").put("geometry", "DROP_PROVIDER_RESPONSE")
        json.getJSONArray("importedTracks").getJSONObject(0).getJSONArray("segments").getJSONObject(0)
            .getJSONArray("points").getJSONObject(0).put("timestamp", "DROP_FOREIGN_TIME").put("heartRate", "DROP_FOREIGN_HEALTH")
        val encoded = BackupJson.encode(BackupJson.decode(json)).toString()
        assertFalse(encoded.contains("DROP_"))
        assertTrue(json.toString().contains("DROP_FOREIGN_TIME"))
    }

    @Test fun malformedNestedValuesAndBrokenReferencesAreRejected() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("savedPlans").getJSONObject(0).put("createdAt", "10") },
            { it.getJSONArray("savedPlans").getJSONObject(0).getJSONObject("preferences").put("avoidTolls", "false") },
            { it.getJSONArray("savedPlans").getJSONObject(0).put("stops", JSONArray()) },
            { it.getJSONArray("importedTracks").getJSONObject(0).getJSONArray("segments").getJSONObject(0).put("trackIndex", 1.5) },
            { it.getJSONArray("importedTracks").getJSONObject(0).getJSONArray("segments").getJSONObject(0).getJSONArray("points").getJSONObject(0).put("latitude", 91.0) },
            { it.getJSONArray("importedTracks").getJSONObject(0).getJSONArray("segments").getJSONObject(0).getJSONArray("points").getJSONObject(0).put("latitude", "10.0") },
            { it.getJSONArray("memberships").getJSONObject(0).put("journeyId", "unknown") },
            { it.getJSONArray("collections").put(it.getJSONArray("collections").getJSONObject(0)) },
        )
        mutations.forEach { mutate ->
            val json = fixture().also(mutate)
            assertThrows(Exception::class.java) { BackupJson.decode(json) }
        }
    }

    private fun fixture(): JSONObject {
        val a = org.iz.navigation.weather.WeatherCoordinate(10.0, 20.0)
        val b = org.iz.navigation.weather.WeatherCoordinate(10.1, 20.1)
        val journey = Journey(id = "trip", startedAt = 10, endedAt = 20)
        return BackupJson.encode(DiarySnapshot(journeys = listOf(journey),
            savedPlans = listOf(SavedRoutePlan(id = "plan", name = "Plan", stops = listOf(org.iz.navigation.weather.RouteStop("A", a), org.iz.navigation.weather.RouteStop("B", b)), transport = Transport.WALK)),
            importedTracks = listOf(org.iz.navigation.gpx.ImportedTrack(id = "track", name = "Track", segments = listOf(org.iz.navigation.gpx.TrackSegment("Section", listOf(a, b), "Group", 0)))),
            collections = listOf(JourneyCollection(id = "collection", name = "Trip")), memberships = listOf(CollectionMembership("collection", journey.id, 0))))
    }

    private fun canonical(value: Any?): Any? = when (value) {
        is JSONArray -> List(value.length()) { canonical(value.get(it)) }
        is JSONObject -> value.keys().asSequence().sorted().associateWith { canonical(value.get(it)) }
        is Number -> value.toDouble()
        else -> value
    }
}
