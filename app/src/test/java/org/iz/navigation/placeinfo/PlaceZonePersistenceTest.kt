package org.iz.navigation.placeinfo

import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.junit.Assert.*
import org.junit.Test

class PlaceZonePersistenceTest {
    private val key=PlaceInfoKey("https://api.openstreetmap.org/api/0.6",OsmRef(OsmType.NODE,42))
    private val values=mutableMapOf<String,String>()
    private fun store()=StoredPlaceZoneOverrides(values::get) { k,v -> if(v==null) values.remove(k) else values[k]=v }
    @Test fun persistedOverrideSurvivesNewAdapterAndResetRemovesIt() {
        store().write(key,"Europe/Istanbul")
        assertEquals("Europe/Istanbul",store().read(key))
        store().write(key,null); assertNull(store().read(key)); assertTrue(values.isEmpty())
    }
    @Test fun endpointAndTypeHaveIndependentLocalOverrides() {
        val other=key.copy(endpoint="https://other.example/api/0.6")
        val way=key.copy(ref=OsmRef(OsmType.WAY,42))
        store().write(key,"Europe/Istanbul"); store().write(other,"UTC"); store().write(way,"Europe/Berlin")
        assertEquals("Europe/Istanbul",store().read(key)); assertEquals("UTC",store().read(other)); assertEquals("Europe/Berlin",store().read(way))
    }
    @Test fun invalidInputCannotReplaceAcceptedOverrideAndInvalidPersistedValueIsIgnored() {
        store().write(key,"UTC")
        assertThrows(IllegalArgumentException::class.java) { store().write(key,"Mars/Invalid") }
        assertEquals("UTC",store().read(key))
        values.replaceAll { _,_ -> "Mars/Invalid" }; assertNull(store().read(key))
    }
}
