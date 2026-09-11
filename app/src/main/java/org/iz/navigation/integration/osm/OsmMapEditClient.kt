package org.iz.navigation.integration.osm

import org.iz.navigation.BuildConfig
import org.iz.navigation.data.*
import org.iz.navigation.integration.OsmServiceEndpoints
import org.iz.navigation.integration.isAllowedOsmEndpoint
import java.io.IOException
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import kotlin.math.cos

data class OsmDuplicateCandidate(val ref: OsmRef, val name: String, val latitude: Double?, val longitude: Double?)
internal data class OsmUploadedNode(val id: Long, val version: Long)
internal data class OsmCreatedNode(val id: Long, val version: Long, val changesetId: Long, val userId: Long,
    val latitude: Double, val longitude: Double, val tags: Map<String, String>)
internal data class OsmChangesetInfo(val id: Long, val userId: Long, val open: Boolean)

internal interface OsmMapEditGateway {
    suspend fun nearby(draft: MapEditDraft): List<OsmDuplicateCandidate> = emptyList()
    suspend fun createChangeset(token: String, draft: MapEditDraft): Long
    suspend fun uploadNode(token: String, draft: MapEditDraft, changesetId: Long): OsmUploadedNode
    suspend fun closeChangeset(token: String, id: Long)
    suspend fun downloadChangeset(id: Long): List<OsmCreatedNode>
    suspend fun readChangeset(id: Long): OsmChangesetInfo
}

internal object OsmMapEditXml {
    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
    private fun tag(key: String, value: String) = "<tag k=\"${escape(key)}\" v=\"${escape(value)}\"/>"
    fun changeset(draft: MapEditDraft): String = "<osm><changeset>" +
        tag("created_by", "Iz ${BuildConfig.VERSION_NAME}") +
        tag("comment", "Yerinde gözlemlenen ${draft.preset.label.lowercase(Locale.forLanguageTag("tr"))} eklendi") +
        tag("source", "survey") + "</changeset></osm>"
    fun nodeUpload(draft: MapEditDraft, changeset: Long): String {
        validateMapEditForPublish(draft); require(changeset > 0)
        return "<osmChange version=\"0.6\" generator=\"Iz\"><create><node id=\"-1\" changeset=\"$changeset\" " +
            "lat=\"${draft.latitude}\" lon=\"${draft.longitude}\">" +
            draft.publicTags().entries.joinToString("") { tag(it.key, it.value) } + "</node></create></osmChange>"
    }

    fun parse(value: String): Element {
        // Android's DOM implementations differ in their supported SAX feature names. Reject DTDs before parsing.
        require(!value.contains("<!DOCTYPE", ignoreCase = true) && !value.contains("<!ENTITY", ignoreCase = true))
        val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false; isNamespaceAware = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IOException("External XML entities are not allowed") }
        return builder.parse(InputSource(StringReader(value))).documentElement.also {
            require(it.getElementsByTagName("error").length == 0)
        }
    }
    fun children(parent: Element, name: String): List<Element> = (0 until parent.childNodes.length)
        .mapNotNull { parent.childNodes.item(it) as? Element }.filter { it.tagName == name }
}

/** Every write is one-shot, HTTPS-only and never redirects the bearer token to another host. */
internal class OsmMapEditClient(
    client: OkHttpClient = OkHttpClient(),
    private val overpassEndpoint: () -> String = { OsmServiceEndpoints().overpass },
) : OsmMapEditGateway {
    private val http = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    override suspend fun createChangeset(token: String, draft: MapEditDraft): Long = withContext(Dispatchers.IO) {
        execute(api("changeset/create", token).put(body(OsmMapEditXml.changeset(draft))).build()).trim().toLong().also { require(it > 0) }
    }
    override suspend fun uploadNode(token: String, draft: MapEditDraft, changesetId: Long): OsmUploadedNode = withContext(Dispatchers.IO) {
        val root = OsmMapEditXml.parse(execute(api("changeset/$changesetId/upload", token)
            .post(body(OsmMapEditXml.nodeUpload(draft, changesetId))).build()))
        require(root.tagName == "diffResult")
        val nodes = OsmMapEditXml.children(root, "node")
        require(nodes.size == 1 && nodes.single().getAttribute("old_id") == "-1")
        OsmUploadedNode(nodes.single().getAttribute("new_id").toLong(), nodes.single().getAttribute("new_version").toLong())
            .also { require(it.id > 0 && it.version == 1L) }
    }
    override suspend fun closeChangeset(token: String, id: Long) = withContext(Dispatchers.IO) {
        require(id > 0); execute(api("changeset/$id/close", token).put(body("")).build()); Unit
    }
    override suspend fun readChangeset(id: Long): OsmChangesetInfo = withContext(Dispatchers.IO) {
        require(id > 0)
        val root = OsmMapEditXml.parse(execute(api("changeset/$id").get().build()))
        val value = OsmMapEditXml.children(root, "changeset").single()
        OsmChangesetInfo(value.getAttribute("id").toLong(), value.getAttribute("uid").toLong(),
            value.getAttribute("open").let { require(it in setOf("true", "false")); it == "true" }).also { require(it.id == id && it.userId > 0) }
    }
    override suspend fun downloadChangeset(id: Long): List<OsmCreatedNode> = withContext(Dispatchers.IO) {
        require(id > 0)
        val root = OsmMapEditXml.parse(execute(api("changeset/$id/download").get().build()))
        require(root.tagName == "osmChange")
        OsmMapEditXml.children(root, "create").flatMap { OsmMapEditXml.children(it, "node") }.map { node ->
            OsmCreatedNode(node.getAttribute("id").toLong(), node.getAttribute("version").toLong(),
                node.getAttribute("changeset").toLong(), node.getAttribute("uid").toLong(),
                node.getAttribute("lat").toDouble(), node.getAttribute("lon").toDouble(),
                OsmMapEditXml.children(node, "tag").associate { it.getAttribute("k") to it.getAttribute("v") })
        }
    }

    /** Explicit form preview/send only. No cache; includes containing mapped areas, not just their centres. */
    override suspend fun nearby(draft: MapEditDraft): List<OsmDuplicateCandidate> = withContext(Dispatchers.IO) {
        validateMapEdit(draft)
        val endpoint = overpassEndpoint(); require(isAllowedOsmEndpoint(endpoint))
        val lat = draft.latitude; val lon = draft.longitude
        val filter = "[\"${draft.preset.key}\"=\"${draft.preset.value}\"]"
        val query = "[out:json][timeout:20][maxsize:2097152];is_in($lat,$lon)->.containing;" +
            "(nwr(around:250,$lat,$lon)$filter;way(pivot.containing)$filter;rel(pivot.containing)$filter;);out center tags;"
        val request = Request.Builder().url(endpoint).header("User-Agent", userAgent)
            .post(oneShot(FormBody.Builder().add("data", query).build())).build()
        val areaCandidates = parseDuplicateCandidates(execute(request), draft.preset)
        // Overpass is replicated. The editing API additionally catches very recent nearby nodes/ways.
        val deltaLat = 250.0 / 111_320.0
        val deltaLon = (deltaLat / cos(Math.toRadians(lat)).coerceAtLeast(0.01)).coerceAtMost(0.1)
        val west = lon - deltaLon; val east = lon + deltaLon
        val spans = when {
            west < -180 -> listOf(-180.0 to east, west + 360 to 180.0)
            east > 180 -> listOf(west to 180.0, -180.0 to east - 360)
            else -> listOf(west to east)
        }
        val currentCandidates = spans.flatMap { (left, right) ->
            val bbox = "$left,${(lat - deltaLat).coerceAtLeast(-90.0)},$right,${(lat + deltaLat).coerceAtMost(90.0)}"
            val root = JSONObject(execute(api("map.json?bbox=$bbox").header("Accept", "application/json").get().build()))
            val elements = root.getJSONArray("elements")
            val filtered = org.json.JSONArray()
            for (index in 0 until elements.length()) {
                val element = elements.getJSONObject(index)
                require(!element.has("error")) { "OSM map response was incomplete" }
                if (element.optJSONObject("tags")?.optString(draft.preset.key) == draft.preset.value) filtered.put(element)
            }
            parseDuplicateCandidates(JSONObject().put("elements", filtered).toString(), draft.preset)
        }
        (areaCandidates + currentCandidates).distinctBy { it.ref }
    }

    private fun api(path: String, token: String? = null) = Request.Builder().url("${OsmNotesClient.API_BASE}/$path")
        .header("User-Agent", userAgent).header("Accept", "application/xml").apply {
            if (token != null) { require(token.isNotBlank()); header("Authorization", "Bearer $token") }
        }
    private fun execute(request: Request): String {
        check(request.url.isHttps)
        if (request.header("Authorization") != null) check(request.url.host == "api.openstreetmap.org")
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw OsmApiException(response.code)
            val source = response.body?.source() ?: throw IOException("OSM response was empty")
            source.request(MAX_BYTES + 1)
            if (source.buffer.size > MAX_BYTES) throw IOException("OSM response exceeded limit")
            source.readUtf8()
        }
    }
    private fun body(xml: String) = oneShot(xml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
    private fun oneShot(delegate: RequestBody) = object : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    }
    companion object {
        private const val MAX_BYTES = 4L * 1024 * 1024
        private val userAgent get() = "Iz/${BuildConfig.VERSION_NAME} (org.iz.navigation; OSM editor)"
    }
}

internal fun parseDuplicateCandidates(json: String, preset: MapPlacePreset): List<OsmDuplicateCandidate> {
    val root = JSONObject(json)
    require(!root.has("remark")) { "Yakındaki yer sorgusu tamamlanmadı." }
    val values = root.getJSONArray("elements")
    require(values.length() < 1000) { "Bu bölgede çok fazla eşleşme var; daha sonra yeniden kontrol et." }
    return (0 until values.length()).map { index ->
        val value = values.getJSONObject(index)
        val type = when (value.getString("type")) { "node" -> OsmType.NODE; "way" -> OsmType.WAY; "relation" -> OsmType.RELATION; else -> error("Invalid OSM element") }
        val id = value.getLong("id"); require(id > 0)
        val tags = value.getJSONObject("tags"); require(tags.optString(preset.key) == preset.value)
        val point = if (type == OsmType.NODE) value else value.optJSONObject("center")
        OsmDuplicateCandidate(OsmRef(type, id), tags.optString("name").ifBlank { preset.label },
            point?.optDouble("lat")?.takeIf(Double::isFinite), point?.optDouble("lon")?.takeIf(Double::isFinite))
    }.distinctBy { it.ref }
}
