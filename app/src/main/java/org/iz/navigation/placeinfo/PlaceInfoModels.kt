package org.iz.navigation.placeinfo

import org.iz.navigation.data.OsmRef

data class PlaceInfoKey(val endpoint: String, val ref: OsmRef)
data class PublicPlaceSnapshot(val key: PlaceInfoKey, val version: Long,
    val tags: Map<String, String>, val fetchedAtMillis: Long)
sealed interface PlaceInfoRead {
    data class Found(val snapshot: PublicPlaceSnapshot) : PlaceInfoRead
    data class Unavailable(val key: PlaceInfoKey, val status: Int) : PlaceInfoRead
}
fun interface PlaceInfoReader { suspend fun read(key: PlaceInfoKey): PlaceInfoRead }
data class PlaceInfoState(val key: PlaceInfoKey? = null, val snapshot: PublicPlaceSnapshot? = null,
    val loading: Boolean = false, val stale: Boolean = false, val unavailableStatus: Int? = null, val failed: Boolean = false)
enum class PlaceTagValue { YES, NO, ONLY, LIMITED, DESIGNATED, UNKNOWN, MISSING }
data class PlaceInfoAction(val label: String, val uri: String, val sourceKeys: Set<String>)
data class PlacePaymentFact(val key: String, val label: String, val value: PlaceTagValue, val rawValue: String)
data class PublicPlacePresentation(val name: String?, val address: String?, val phones: List<PlaceInfoAction>,
    val websites: List<PlaceInfoAction>, val invalidPhoneValues: List<String>, val invalidWebsiteValues: List<String>,
    val wheelchair: PlaceTagValue, val wheelchairRaw: String?, val payments: List<PlacePaymentFact>,
    val openingHours: String?, val timezone: String?)
