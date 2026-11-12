package org.iz.navigation.placeinfo

import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.junit.Assert.*
import org.junit.Test

class PublicPlaceMapperTest {
    private fun mapped(vararg tags: Pair<String, String>) = PublicPlaceMapper.map(PublicPlaceSnapshot(
        PlaceInfoKey("https://api.openstreetmap.org/api/0.6", OsmRef(OsmType.NODE, 42)), 3, tags.toMap(), 1000))

    @Test fun legalUrlSemicolonsRemainPartOfTheDestinationIncludingEmbeddedUrlText() {
        for (url in listOf("https://example.org/catalog;lang=tr", "https://example.org/?a=one;b=two",
            "https://example.org/path;https://other.example/")) {
            val value = mapped("website" to url)
            assertEquals(listOf(url), value.websites.map { it.uri })
            assertTrue(value.invalidWebsiteValues.isEmpty())
        }
    }

    @Test fun equalContactAliasesDedupeButConflictingValuesRemainSelectable() {
        val value = mapped("phone" to "+90 (312) 123-45-67; 0312 765 43 21", "contact:phone" to "+903121234567",
            "website" to "https://EXAMPLE.org", "contact:website" to "https://example.org/; https://other.example/path")
        assertEquals(listOf("tel:+903121234567", "tel:03127654321"), value.phones.map { it.uri })
        assertEquals(setOf("phone", "contact:phone"), value.phones.first().sourceKeys)
        assertEquals(listOf("https://example.org/", "https://other.example/path"), value.websites.map { it.uri })
        assertEquals(setOf("website", "contact:website"), value.websites.first().sourceKeys)
    }

    @Test fun unsafeContactValuesAreTextOnlyAndNeverBecomeActions() {
        val value = mapped("phone" to "*123#;tel:123;123\n456", "website" to
            "javascript:alert(1);intent://bad;https://user:password@example.org;https://example.org/\npath;www.example.org")
        assertTrue(value.phones.isEmpty()); assertTrue(value.websites.isEmpty())
        assertEquals(3, value.invalidPhoneValues.size)
        assertEquals(5, value.invalidWebsiteValues.size)
    }

    @Test fun paymentFactsPreserveExplicitNoOnlyAndUnknownWithoutInventingOtherMethods() {
        val value = mapped("payment:cash" to "only", "payment:visa" to "no", "payment:contactless" to "yes",
            "payment:local_token" to "sometimes")
        assertEquals(4, value.payments.size)
        assertEquals(PlaceTagValue.ONLY, value.payments.single { it.key == "payment:cash" }.value)
        assertEquals(PlaceTagValue.NO, value.payments.single { it.key == "payment:visa" }.value)
        assertEquals(PlaceTagValue.YES, value.payments.single { it.key == "payment:contactless" }.value)
        val raw = value.payments.single { it.key == "payment:local_token" }
        assertEquals(PlaceTagValue.UNKNOWN, raw.value); assertEquals("sometimes", raw.rawValue)
        assertFalse(value.payments.any { it.key == "payment:debit_cards" })
    }

    @Test fun missingAndUnsupportedAccessibilityAreDifferentAndPublicAddressIsNotInvented() {
        val empty = mapped()
        assertNull(empty.name); assertNull(empty.address)
        assertEquals(PlaceTagValue.MISSING, empty.wheelchair)
        assertTrue(empty.payments.isEmpty())
        assertEquals(PlaceTagValue.DESIGNATED, mapped("wheelchair" to "designated").wheelchair)
        val unknown = mapped("wheelchair" to "private access only")
        assertEquals(PlaceTagValue.UNKNOWN, unknown.wheelchair)
        assertEquals("private access only", unknown.wheelchairRaw)
        val named = mapped("name" to "Public venue", "addr:street" to "Main Street", "addr:housenumber" to "10",
            "addr:postcode" to "06000", "addr:city" to "Ankara")
        assertEquals("Public venue", named.name)
        assertEquals("Main Street 10, 06000 Ankara", named.address)
    }
}
