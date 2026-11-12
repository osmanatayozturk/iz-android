package org.iz.navigation.placeinfo

import okhttp3.OkHttpClient
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.io.StringReader
import java.net.Proxy
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Clock
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PublicPlaceApiClient(private val clock: Clock = Clock.systemUTC(),
    client: OkHttpClient = OkHttpClient(), private val allowLoopbackHttp: Boolean = false) : PlaceInfoReader {
    private val http = client.newBuilder().apply { interceptors().clear(); networkInterceptors().clear() }
        .cookieJar(CookieJar.NO_COOKIES).cache(null).proxy(Proxy.NO_PROXY)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).eventListener(EventListener.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val attempt = requireNotNull(chain.request().tag(AtomicBoolean::class.java))
            if (!attempt.compareAndSet(false, true)) throw IOException("Tekrarlanan OSM okuması durduruldu.")
            chain.proceed(chain.request())
        }.build()

    override suspend fun read(key: PlaceInfoKey): PlaceInfoRead {
        require(key.ref.id > 0)
        val uri = URI(key.endpoint)
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.split('/').none { it in setOf(".", "..") })
        val base = key.endpoint.toHttpUrl()
        require(base.isHttps || allowLoopbackHttp && base.host in setOf("127.0.0.1", "localhost", "::1"))
        val url = base.newBuilder().apply { if (base.encodedPath.endsWith('/')) removePathSegment(base.pathSize - 1) }
            .addPathSegment(key.ref.type.name.lowercase(Locale.ROOT)).addPathSegment(key.ref.id.toString()).build()
        val request = Request.Builder().url(url).header("User-Agent", "Iz (public OSM place reader)")
            .header("Accept", "application/xml").tag(AtomicBoolean::class.java, AtomicBoolean()).get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("OSM bilgileri alınamadı."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (it.code == 404 || it.code == 410) PlaceInfoRead.Unavailable(key, it.code)
                            else {
                                if (it.code != 200) throw IOException("OSM bilgileri alınamadı (HTTP ${it.code}).")
                                val body = it.body ?: throw IOException("OSM yanıtı boş.")
                                require(body.contentLength() <= MAX_BYTES)
                                val source = body.source(); source.request(MAX_BYTES + 1)
                                require(source.buffer.size <= MAX_BYTES)
                                val xml = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source.readByteArray())).toString()
                                PublicPlaceXml.read(xml, key, clock.millis()) { check(continuation.isActive) }
                            }
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("OSM yanıtı doğrulanamadı."))
                    }
                }
            })
        }
    }

    private companion object { const val MAX_BYTES = 1_048_576L }
}

/** Single public element, tags only. No DTD/entities, nested objects or recursive geometry.
 * https://wiki.openstreetmap.org/wiki/API_v0.6#Read:_GET_/api/0.6/[node|way|relation]/#id
 */
internal object PublicPlaceXml {
    fun read(xml: String, key: PlaceInfoKey, fetchedAt: Long, checkActive: () -> Unit): PlaceInfoRead {
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
        var depth = 0; var objects = 0; var version = 0L; var visible = true
        val tags = linkedMapOf<String, String>()
        val expected = key.ref.type.name.lowercase(Locale.ROOT)
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw IOException("Harici XML yasak.")
            override fun startElement(uri: String?, localName: String?, name: String, attributes: Attributes) {
                checkActive(); depth++
                when (depth) {
                    1 -> require(name == "osm")
                    2 -> {
                        objects++; require(objects == 1 && name == expected)
                        require(attributes.getValue("id")?.toLongOrNull() == key.ref.id)
                        version = requireNotNull(attributes.getValue("version")?.toLongOrNull()); require(version > 0)
                        visible = when (attributes.getValue("visible")) { null, "true" -> true; "false" -> false; else -> error("visible") }
                    }
                    3 -> when (name) {
                        "tag" -> {
                            val k = requireNotNull(attributes.getValue("k")); val v = requireNotNull(attributes.getValue("v"))
                            require(k.isNotBlank() && k.codePointCount(0, k.length) <= 255 && v.codePointCount(0, v.length) <= 255)
                            require(tags.size < 1024 && !tags.containsKey(k)); tags[k] = v
                        }
                        "nd" -> require(expected == "way")
                        "member" -> require(expected == "relation")
                        else -> error("Beklenmeyen OSM içeriği")
                    }
                    else -> error("OSM XML derinliği aşıldı")
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) { depth-- }
            override fun characters(ch: CharArray, start: Int, length: Int) { require((start until start + length).all { ch[it].isWhitespace() }) }
        }
        val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }.newSAXParser()
        parser.parse(InputSource(StringReader(xml)), handler)
        require(depth == 0 && objects == 1)
        return if (visible) PlaceInfoRead.Found(PublicPlaceSnapshot(key, version, tags.toMap(), fetchedAt))
        else PlaceInfoRead.Unavailable(key, 410)
    }
}
