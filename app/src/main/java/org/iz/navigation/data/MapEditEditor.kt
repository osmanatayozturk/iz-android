package org.iz.navigation.data

import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.iz.navigation.osmcatalog.OsmPresetRuleSnapshot
import org.iz.navigation.osmcatalog.OsmPresetTagTransition
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.json.JSONObject

/** Local UI/saved-place label only; never substitute this for the public OSM name tag. */
fun MapEditDraft.displayLabel(): String = name.ifBlank {
    if (editorVersion == 2) {
        runCatching {
            selectedPresetSnapshot()?.let { JSONObject(it.ruleJson).getString("name") }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "Tür seçilmemiş OSM mekânı"
    } else preset.label
}

fun newMapEditDraft(latitude: Double, longitude: Double, name: String = "") = MapEditDraft(
    latitude = latitude, longitude = longitude, editorVersion = 2, name = name.trim(),
    nameAction = if (name.isBlank()) MapEditFieldAction.UNTOUCHED else MapEditFieldAction.SET,
)

fun MapEditDraft.selectPreset(snapshot: OsmPresetRuleSnapshot): MapEditDraft {
    require(editorVersion == 2 && snapshot.geometry == editorGeometry())
    val encoded = snapshot.toJson()
    return copy(presetId = snapshot.presetId, presetSnapshot = encoded,
        typeChanged = targetType != null && encoded != originalPresetSnapshot)
}

internal fun MapEditDraft.editorGeometry(): OsmPresetGeometry =
    if (targetType == OsmType.WAY) OsmPresetGeometry.AREA else OsmPresetGeometry.POINT

fun MapEditDraft.selectedPresetSnapshot(): OsmPresetRuleSnapshot? =
    presetSnapshot?.let(OsmPresetRuleSnapshot::fromJson)

internal fun MapEditDraft.validateEditorDraft() {
    require(editorVersion in 1..2)
    if (editorVersion == 1) {
        require(presetId == null && presetSnapshot == null && originalPresetSnapshot == null && phone.isEmpty() &&
            nameAction == MapEditFieldAction.UNTOUCHED && phoneAction == MapEditFieldAction.UNTOUCHED && !typeChanged && !legacyAddressIntent)
        return
    }
    require((presetId == null) == (presetSnapshot == null))
    selectedPresetSnapshot()?.let {
        require(it.presetId == presetId && it.geometry == editorGeometry()) { "Tür bu geometri için kullanılamıyor." }
    }
    originalPresetSnapshot?.let {
        require(targetType != null && OsmPresetRuleSnapshot.fromJson(it).geometry == editorGeometry())
    }
    require(phone.codePointCount(0, phone.length) <= if (phoneAction == MapEditFieldAction.UNTOUCHED) 1024 else 255)
    require(phone.none { it.code < 32 || it.code == 127 }) { "Telefon alanındaki geçersiz karakterleri kaldır." }
    if (phoneAction == MapEditFieldAction.SET) require(phone.isNotBlank())
    if (phoneAction == MapEditFieldAction.DELETE) require(phone.isEmpty())
    if (nameAction == MapEditFieldAction.SET) require(name.isNotBlank())
    if (nameAction == MapEditFieldAction.DELETE) require(name.isEmpty())
    if (typeChanged) require(targetType != null && presetSnapshot != null)
}

internal fun MapEditDraft.hasEditorIntent() = editorVersion == 2 &&
    (typeChanged || nameAction != MapEditFieldAction.UNTOUCHED || phoneAction != MapEditFieldAction.UNTOUCHED ||
        legacyAddressIntent && (street.isNotBlank() || houseNumber.isNotBlank()))

internal fun MapEditDraft.editorTags(existing: Map<String, String>): Map<String, String> {
    validateEditorDraft()
    val tags = if (targetType == null || typeChanged) {
        val next = requireNotNull(selectedPresetSnapshot()) { "Bir tür seç." }
        val old = if (targetType == null) null else originalPresetSnapshot?.let(OsmPresetRuleSnapshot::fromJson)
        OsmPresetTagTransition.apply(existing, old, next).toMutableMap()
    } else existing.toMutableMap()
    when (nameAction) {
        MapEditFieldAction.SET -> tags["name"] = name.trim()
        MapEditFieldAction.DELETE -> tags.remove("name")
        MapEditFieldAction.UNTOUCHED -> Unit
    }
    when (phoneAction) {
        MapEditFieldAction.SET -> { tags["phone"] = phone.trim(); tags.remove("contact:phone") }
        MapEditFieldAction.DELETE -> { tags.remove("phone"); tags.remove("contact:phone") }
        MapEditFieldAction.UNTOUCHED -> Unit
    }
    if (legacyAddressIntent) {
        if (street.isNotBlank()) tags["addr:street"] = street.trim()
        if (houseNumber.isNotBlank()) tags["addr:housenumber"] = houseNumber.trim()
    }
    return tags.toSortedMap()
}

internal fun MapEditDraft.editorPublicTags(): Map<String, String> =
    if (targetType == null) editorTags(emptyMap()) else buildMap {
        if (typeChanged) putAll(OsmPresetTagTransition.apply(emptyMap(), null, requireNotNull(selectedPresetSnapshot())))
        if (nameAction == MapEditFieldAction.SET) put("name", name.trim())
        if (phoneAction == MapEditFieldAction.SET) put("phone", phone.trim())
        if (legacyAddressIntent) {
            if (street.isNotBlank()) put("addr:street", street.trim())
            if (houseNumber.isNotBlank()) put("addr:housenumber", houseNumber.trim())
        }
    }

/** A resumed v1 node keeps its original enum recipe until an explicit new type is selected. */
fun MapEditDraft.upgradeEditorDraft(catalog: OsmPresetCatalog): MapEditDraft {
    if (editorVersion == 2) return this
    require(targetType == null) { "Mevcut nesnenin güncel bilgileriyle taslağı aç." }
    val id = "${preset.key}/${preset.value}"
    val definition = requireNotNull(catalog.resolve(id)) { "Eski taslağın türü katalogda bulunamadı." }
    val tags = JSONObject().put(preset.key, preset.value)
    val rule = JSONObject(definition.ruleJson).put("tags", tags).put("addTags", tags).put("removeTags", tags)
        .put("legacyEnumPreset", preset.name)
    val frozen = OsmPresetRuleSnapshot(catalog.commit, catalog.sha256, id, OsmPresetGeometry.POINT, rule.toString())
    return copy(editorVersion = 2, presetId = id, presetSnapshot = frozen.toJson(),
        nameAction = if (name.isBlank()) MapEditFieldAction.UNTOUCHED else MapEditFieldAction.SET,
        legacyAddressIntent = street.isNotBlank() || houseNumber.isNotBlank())
}
