package org.iz.navigation.integration.osm

import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapPlacePreset
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.validateMapEditForPublish
import org.iz.navigation.data.publicChanges
import org.iz.navigation.data.surveyObservation
import org.iz.navigation.data.editorTags
import org.iz.navigation.data.MapEditFieldAction
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.iz.navigation.osmcatalog.OsmPresetRuleSnapshot
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
        if (draft.editorVersion == 2) {
            editorGeometry(draft.originalPresetSnapshot?.let(OsmPresetRuleSnapshot::fromJson))
            require(version < Long.MAX_VALUE)
            val corrected = copy(tags = draft.editorTags(tags))
            val fields = (tags.keys + corrected.tags.keys).sorted().mapNotNull { key ->
                if (tags[key] == corrected.tags[key]) null else OsmCorrectionField(key, tags[key], corrected.tags[key])
            }
            require(fields.isNotEmpty()) { "En az bir bilgiyi değiştir." }
            return OsmCorrectionReview(draft, this, corrected, credentials, fields)
        }
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

internal fun OsmCorrectionElement.editorTopology(): OsmPresetGeometry {
    if (ref.type == OsmType.NODE) return OsmPresetGeometry.POINT
    if (ref.type != OsmType.WAY || tags["area"] == "no" || nodeRefs.size < 4 ||
        nodeRefs.first() != nodeRefs.last() || nodeRefs.dropLast(1).toSet().size != nodeRefs.size - 1) {
        throw OsmUnsupportedCorrection()
    }
    return OsmPresetGeometry.AREA
}

/** Closure is topology only. An area editor additionally needs a matching frozen area recipe. */
internal fun OsmCorrectionElement.editorGeometry(snapshot: OsmPresetRuleSnapshot? = null): OsmPresetGeometry {
    val geometry = editorTopology()
    if (geometry == OsmPresetGeometry.AREA && snapshot?.providesAreaEvidence(tags) != true) throw OsmUnsupportedCorrection()
    return geometry
}

internal fun OsmCorrectionElement.editorGeometry(catalog: OsmPresetCatalog): OsmPresetGeometry {
    val geometry = editorTopology()
    if (geometry == OsmPresetGeometry.POINT) return geometry
    val recognized = catalog.identify(tags, geometry).firstOrNull { record ->
        record.tags.keys.any { it != "area" && it != "name" && !it.startsWith("addr:") }
    } ?: throw OsmUnsupportedCorrection()
    val frozen = OsmPresetRuleSnapshot(catalog.commit, catalog.sha256, recognized.id, geometry, recognized.ruleJson)
    return editorGeometry(frozen)
}

internal fun OsmCorrectionElement.phoneText(): String = listOfNotNull(tags["phone"], tags["contact:phone"])
    .filter(String::isNotBlank).distinct().joinToString("; ")

internal fun OsmCorrectionElement.editorDraft(catalog: OsmPresetCatalog, id: String, latitude: Double, longitude: Double): MapEditDraft {
    val geometry = editorGeometry(catalog)
    val recognized = catalog.identify(tags, geometry).firstOrNull { record ->
        geometry == OsmPresetGeometry.POINT || record.tags.keys.any { it != "area" && it != "name" && !it.startsWith("addr:") }
    }
    val frozen = recognized?.let { OsmPresetRuleSnapshot(catalog.commit, catalog.sha256, it.id, geometry, it.ruleJson).toJson() }
    return MapEditDraft(id = id, latitude = latitude, longitude = longitude, editorVersion = 2,
        targetType = ref.type, targetId = ref.id, name = tags["name"].orEmpty(), phone = phoneText(),
        presetId = recognized?.id, presetSnapshot = frozen, originalPresetSnapshot = frozen)
}

internal fun OsmCorrectionElement.upgradeEditorDraft(draft: MapEditDraft, catalog: OsmPresetCatalog): MapEditDraft {
    if (draft.editorVersion == 2) return draft
    val upgraded = editorDraft(catalog, draft.id, draft.latitude, draft.longitude)
    return draft.copy(editorVersion = 2, presetId = upgraded.presetId, presetSnapshot = upgraded.presetSnapshot,
        originalPresetSnapshot = upgraded.originalPresetSnapshot, phone = upgraded.phone,
        name = draft.name.ifBlank { upgraded.name },
        nameAction = if (draft.name.isBlank()) MapEditFieldAction.UNTOUCHED else MapEditFieldAction.SET,
        legacyAddressIntent = draft.street.isNotBlank() || draft.houseNumber.isNotBlank())
}

/** Explicit user refresh only: preserve edited fields, replace origin evidence, and require new consent. */
internal fun OsmCorrectionElement.rebaseEditorDraft(draft: MapEditDraft, catalog: OsmPresetCatalog): MapEditDraft {
    require(ref == draft.correctionRef())
    require(draft.surveyPayload == null) { "Yerinde gözlem taslağını kendi formunda incele." }
    val upgraded = if (draft.editorVersion == 2) draft else upgradeEditorDraft(draft, catalog)
    val editable = upgraded.editableCopy()
    val fresh = editorDraft(catalog, draft.id, draft.latitude, draft.longitude)
    val selected = if (editable.typeChanged) editable.presetSnapshot else fresh.presetSnapshot
    return editable.copy(
        presetId = if (editable.typeChanged) editable.presetId else fresh.presetId,
        presetSnapshot = selected, originalPresetSnapshot = fresh.originalPresetSnapshot,
        typeChanged = editable.typeChanged && selected != fresh.originalPresetSnapshot,
        name = if (editable.nameAction == MapEditFieldAction.UNTOUCHED) fresh.name else editable.name,
        phone = if (editable.phoneAction == MapEditFieldAction.UNTOUCHED) fresh.phone else editable.phone,
        surveyConfirmed = false,
    )
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
