package org.iz.navigation.integration.osm

import org.iz.navigation.BuildConfig
import org.iz.navigation.data.*
import java.io.IOException
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal data class OsmUploadedNode(val id: Long, val version: Long)
internal data class OsmCreatedNode(val id: Long, val version: Long, val changesetId: Long, val userId: Long,
    val latitude: Double, val longitude: Double, val tags: Map<String, String>)
internal data class OsmChangesetInfo(val id: Long, val userId: Long, val open: Boolean)
internal class OsmMapEditNotDispatched : IOException("OSM map edit authorization changed before dispatch")

internal interface OsmMapEditGateway {
    suspend fun createChangeset(token: String, draft: MapEditDraft): Long
    suspend fun createChangeset(token: String, draft: MapEditDraft, canDispatch: () -> Boolean): Long {
        if (!canDispatch()) throw OsmMapEditNotDispatched()
        return createChangeset(token, draft)
    }
    suspend fun uploadNode(token: String, draft: MapEditDraft, changesetId: Long): OsmUploadedNode
    suspend fun uploadNode(token: String, draft: MapEditDraft, changesetId: Long, canDispatch: () -> Boolean): OsmUploadedNode {
        if (!canDispatch()) throw OsmMapEditNotDispatched()
        return uploadNode(token, draft, changesetId)
    }
    suspend fun closeChangeset(token: String, id: Long, canDispatch: () -> Boolean)
    suspend fun downloadChangeset(id: Long): List<OsmCreatedNode>
    suspend fun downloadSingleCreatedNode(id: Long): OsmCreatedNode?
    suspend fun readChangeset(id: Long): OsmChangesetInfo
}
internal interface OsmMapCorrectionGateway : OsmMapEditGateway {
    suspend fun readElement(ref: OsmRef): OsmCorrectionElement
    suspend fun updateElement(token: String, element: OsmCorrectionElement, changeset: Long, canDispatch: () -> Boolean): Long
    suspend fun downloadCorrection(changeset: Long): OsmCorrectionElement?
}

internal object OsmMapEditXml {
    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
    private fun tag(key: String, value: String) = "<tag k=\"${escape(key)}\" v=\"${escape(value)}\"/>"
    fun changeset(draft: MapEditDraft): String = "<osm><changeset>" +
        tag("created_by", "Iz ${BuildConfig.VERSION_NAME}") +
        tag("comment", when {
            draft.targetType != null -> "Yerinde doğrulanan mekân bilgileri güncellendi"
            draft.editorVersion == 2 -> "Yerinde gözlemlenen OSM noktası eklendi"
            else -> "Yerinde gözlemlenen ${draft.preset.label.lowercase(Locale.forLanguageTag("tr"))} eklendi"
        }) +
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
    fun elements(parent: Element): List<Element> = (0 until parent.childNodes.length)
        .mapNotNull { parent.childNodes.item(it) as? Element }
    fun children(parent: Element, name: String): List<Element> = elements(parent).filter { it.tagName == name }
}

/** Every write is one-shot, HTTPS-only and never redirects the bearer token to another host. */
internal class OsmMapEditClient(
    client: OkHttpClient = OkHttpClient(),
) : OsmMapCorrectionGateway {
    private class DispatchGuard(val canDispatch: () -> Boolean) {
        val started = AtomicBoolean()
    }

    private val http = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            request.tag(DispatchGuard::class.java)?.let { guard ->
                if (!guard.started.compareAndSet(false, true)) throw IOException("Repeated map edit exchange")
                if (!guard.canDispatch()) throw OsmMapEditNotDispatched()
            }
            chain.proceed(request)
        }.build()

    override suspend fun createChangeset(token: String, draft: MapEditDraft): Long = createMapChangeset(token, draft, null)

    override suspend fun createChangeset(token: String, draft: MapEditDraft, canDispatch: () -> Boolean): Long =
        createMapChangeset(token, draft, canDispatch)

    private suspend fun createMapChangeset(token: String, draft: MapEditDraft, canDispatch: (() -> Boolean)?): Long =
        withContext(Dispatchers.IO) {
            val request = api("changeset/create", token).put(body(OsmMapEditXml.changeset(draft))).build()
            execute(request, canDispatch).trim().toLong().also { require(it > 0) }
        }
    override suspend fun uploadNode(token: String, draft: MapEditDraft, changesetId: Long): OsmUploadedNode =
        uploadNode(token, draft, changesetId) { true }

    override suspend fun uploadNode(token: String, draft: MapEditDraft, changesetId: Long, canDispatch: () -> Boolean): OsmUploadedNode = withContext(Dispatchers.IO) {
        val root = OsmMapEditXml.parse(execute(api("changeset/$changesetId/upload", token)
            .post(body(OsmMapEditXml.nodeUpload(draft, changesetId))).build(), canDispatch))
        require(root.tagName == "diffResult")
        val nodes = OsmMapEditXml.children(root, "node")
        require(nodes.size == 1 && nodes.single().getAttribute("old_id") == "-1")
        OsmUploadedNode(nodes.single().getAttribute("new_id").toLong(), nodes.single().getAttribute("new_version").toLong())
            .also { require(it.id > 0 && it.version == 1L) }
    }
    override suspend fun closeChangeset(token: String, id: Long, canDispatch: () -> Boolean) =
        withContext(Dispatchers.IO) {
            require(id > 0)
            execute(api("changeset/$id/close", token).put(body("")).build(), canDispatch)
            Unit
        }
    override suspend fun readChangeset(id: Long): OsmChangesetInfo = withContext(Dispatchers.IO) {
        require(id > 0)
        val root = OsmMapEditXml.parse(execute(api("changeset/$id").header("Cache-Control", "no-cache").get().build()))
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

    override suspend fun downloadSingleCreatedNode(id: Long): OsmCreatedNode? = withContext(Dispatchers.IO) {
        val created = singleChangesetElement(id, "create")?.takeIf { it.tagName == "node" }
            ?.readCorrectionElement() ?: return@withContext null
        OsmCreatedNode(created.ref.id, created.version, created.changesetId, requireNotNull(created.userId),
            requireNotNull(created.latitude).toDouble(), requireNotNull(created.longitude).toDouble(), created.tags)
    }

    override suspend fun readElement(ref: OsmRef): OsmCorrectionElement = withContext(Dispatchers.IO) {
        require(ref.id > 0)
        if (ref.type == OsmType.RELATION) throw OsmUnsupportedCorrection()
        val xml = try {
            execute(api("${ref.type.apiName()}/${ref.id}").header("Cache-Control", "no-cache").get().build())
        } catch (error: OsmApiException) {
            if (error.statusCode in setOf(404, 410)) throw OsmUnsupportedCorrection()
            throw error
        }
        val root = OsmMapEditXml.parse(xml)
        require(root.tagName == "osm")
        OsmMapEditXml.elements(root).single().readCorrectionElement().also { require(it.ref == ref) }
    }

    override suspend fun updateElement(token: String, element: OsmCorrectionElement, changeset: Long, canDispatch: () -> Boolean): Long = withContext(Dispatchers.IO) {
        val root = OsmMapEditXml.parse(execute(api("changeset/$changeset/upload", token)
            .post(body(element.uploadXml(changeset))).build(), canDispatch))
        require(root.tagName == "diffResult")
        val result = OsmMapEditXml.elements(root).single()
        require(result.tagName == element.ref.type.apiName())
        require(result.getAttribute("old_id").toLong() == element.ref.id && result.getAttribute("new_id").toLong() == element.ref.id)
        result.getAttribute("new_version").toLong().also { require(it == element.version + 1) }
    }

    override suspend fun downloadCorrection(changeset: Long): OsmCorrectionElement? = withContext(Dispatchers.IO) {
        singleChangesetElement(changeset, "modify")?.readCorrectionElement()
    }

    private fun singleChangesetElement(changeset: Long, action: String): Element? {
        require(changeset > 0)
        val root = OsmMapEditXml.parse(execute(api("changeset/$changeset/download").header("Cache-Control", "no-cache").get().build()))
        require(root.tagName == "osmChange")
        return OsmMapEditXml.elements(root).singleOrNull()?.takeIf { it.tagName == action }?.let { section ->
            OsmMapEditXml.elements(section).singleOrNull()
        }
    }

    private fun api(path: String, token: String? = null) = Request.Builder().url("${OsmNotesClient.API_BASE}/$path")
        .header("User-Agent", userAgent).header("Accept", "application/xml").apply {
            if (token != null) { require(token.isNotBlank()); header("Authorization", "Bearer $token") }
        }
    private fun execute(request: Request, canDispatch: (() -> Boolean)? = null): String {
        check(request.url.isHttps)
        if (request.header("Authorization") != null) check(request.url.host == "api.openstreetmap.org")
        val dispatched = if (canDispatch == null) request else {
            if (!canDispatch()) throw OsmMapEditNotDispatched()
            request.newBuilder().tag(DispatchGuard::class.java, DispatchGuard(canDispatch)).build()
        }
        return http.newCall(dispatched).execute().use { response ->
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
