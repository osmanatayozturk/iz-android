package org.iz.navigation.integration.osm

import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapPlacePreset
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.validateMapEditForPublish
import org.iz.navigation.data.publicChanges
import org.iz.navigation.data.surveyObservation
import java.util.Locale
import okio.ByteString.Companion.encodeUtf8
import org.w3c.dom.Element

internal class OsmUnsupportedCorrection : IllegalArgumentException()

internal fun osmCorrectionPreset(type: OsmType, nodeRefs: List<Long>, tags: Map<String, String>): MapPlacePreset {
    if (type != OsmType.NODE && type != OsmType.WAY) throw OsmUnsupportedCorrection()
    if (type == OsmType.WAY && (tags["area"] == "no" || nodeRefs.size < 4 ||
            nodeRefs.first() != nodeRefs.last() || nodeRefs.dropLast(1).toSet().size != nodeRefs.size - 1)) {
        throw OsmUnsupportedCorrection()
    }
    return MapPlacePreset.entries.singleOrNull { tags[it.key] == it.value } ?: throw OsmUnsupportedCorrection()
}

internal fun OsmType.apiName() = name.lowercase(Locale.ROOT)

internal fun MapEditDraft.correctionRef(): OsmRef? =
    targetType?.let { type -> targetId?.let { OsmRef(type, it) } }

internal data class OsmCorrectionField(val key: String, val before: String?, val after: String?)

/** Preview is session-bound and deliberately held only in memory. */
internal class OsmCorrectionReview(
    val draft: MapEditDraft,
    val original: OsmCorrectionElement,
    val corrected: OsmCorrectionElement,
    val credentials: OsmCredentials,
    val fields: List<OsmCorrectionField>,
)

internal data class OsmCorrectionElement(
    val ref: OsmRef,
    val version: Long,
    val changesetId: Long,
    val userId: Long?,
    val latitude: String?,
    val longitude: String?,
    val nodeRefs: List<Long>,
    val tags: Map<String, String>,
) {
    fun preset(): MapPlacePreset = osmCorrectionPreset(ref.type, nodeRefs, tags)

    fun review(draft: MapEditDraft, credentials: OsmCredentials): OsmCorrectionReview {
        validateMapEditForPublish(draft)
        require(ref == draft.correctionRef())
        if (preset() != draft.preset) throw OsmUnsupportedCorrection()
        require(version < Long.MAX_VALUE)
        val nameFields = listOf("name" to draft.name, "addr:street" to draft.street, "addr:housenumber" to draft.houseNumber)
            .map { (key, input) -> OsmCorrectionField(key, tags[key], input.trim().ifEmpty { tags[key] }) }
        val observation = draft.surveyObservation()
        val surveyFields = observation?.let {
            require(it.baseVersion == version) { "OSM mekânı değişti. Yeniden incele." }
            it.publicChanges(tags).map { (key, value) -> OsmCorrectionField(key, tags[key], value) }
        }.orEmpty()
        val fields = nameFields + surveyFields
        require(fields.any { it.before != it.after }) { "En az bir bilgiyi değiştir." }
        val corrected = copy(tags = tags + fields.mapNotNull { field -> field.after?.let { field.key to it } })
        return OsmCorrectionReview(draft, this, corrected, credentials, fields)
    }

    // Version, changeset and author are matched independently during reconciliation.
    fun fingerprint(): String = xml("").encodeUtf8().sha256().hex()

    fun uploadXml(changeset: Long): String {
        require(changeset > 0)
        return "<osmChange version=\"0.6\" generator=\"Iz\"><modify>" +
            xml(" version=\"$version\" changeset=\"$changeset\"") + "</modify></osmChange>"
    }

    private fun xml(metadata: String) = buildString {
        val kind = ref.type.apiName()
        append("<$kind id=\"${ref.id}\"$metadata")
        if (ref.type == OsmType.NODE) append(" lat=\"$latitude\" lon=\"$longitude\"")
        append(">")
        nodeRefs.forEach { append("<nd ref=\"$it\"/>") }
        tags.toSortedMap().forEach { (key, value) ->
            append("<tag k=\"${xmlAttribute(key)}\" v=\"${xmlAttribute(value)}\"/>")
        }
        append("</$kind>")
    }
}

private fun xmlAttribute(value: String) = OsmMapEditXml.escape(value)
    .replace("\t", "&#9;").replace("\n", "&#10;").replace("\r", "&#13;")

internal fun Element.readCorrectionElement(): OsmCorrectionElement {
    val type = when (tagName) {
        "node" -> OsmType.NODE
        "way" -> OsmType.WAY
        else -> throw OsmUnsupportedCorrection()
    }
    require(!hasAttribute("visible") || getAttribute("visible") == "true")
    fun positive(name: String) = getAttribute(name).toLong().also { require(it > 0) }
    val children = OsmMapEditXml.elements(this)
    require(children.all { it.tagName == "tag" || (type == OsmType.WAY && it.tagName == "nd") })
    val tags = OsmMapEditXml.children(this, "tag").map { tag ->
        require(tag.hasAttribute("k") && tag.hasAttribute("v"))
        tag.getAttribute("k") to tag.getAttribute("v")
    }
    require(tags.map { it.first }.toSet().size == tags.size)
    val nodeRefs = OsmMapEditXml.children(this, "nd").map { it.getAttribute("ref").toLong().also { ref -> require(ref > 0) } }
    return OsmCorrectionElement(
        ref = OsmRef(type, positive("id")),
        version = positive("version"),
        changesetId = positive("changeset"),
        userId = if (hasAttribute("uid")) positive("uid") else null,
        latitude = if (type == OsmType.NODE) readCoordinate("lat", 90) else null,
        longitude = if (type == OsmType.NODE) readCoordinate("lon", 180) else null,
        nodeRefs = nodeRefs,
        tags = tags.toMap(),
    )
}

private val osmCoordinate = Regex("-?[0-9]{1,3}(\\.[0-9]{1,7})?")

private fun Element.readCoordinate(attribute: String, limit: Int): String {
    val value = getAttribute(attribute)
    require(osmCoordinate.matches(value) && value.toDouble() in -limit.toDouble()..limit.toDouble())
    return value.toBigDecimal().stripTrailingZeros().toPlainString()
}
