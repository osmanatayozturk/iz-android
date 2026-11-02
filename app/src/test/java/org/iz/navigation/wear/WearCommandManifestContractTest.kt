package org.iz.navigation.wear

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.iz.navigation.wearprotocol.WatchHealthProtocol
import org.iz.navigation.wearprotocol.WearProtocol
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Source XML contract only: this does not execute Android IntentFilter or prove device dispatch. */
class WearCommandManifestContractTest {
    @Test fun sourceManifestCoversEverySupportedCommandAndHealthExactly() {
        validSource()
    }

    @Test fun removingAnySupportedPathIncludingV7IsRejected() {
        for (path in expectedPaths()) {
            val document = validSource()
            val filter = listener(document).children("intent-filter").single()
            filter.removeChild(filter.children("data").single { it.android("path") == path })
            assertFalse("Missing authoritative path must fail: $path", violations(document).isEmpty())
        }
    }

    @Test fun broadMatchersAndNearPathsCannotReplaceOrSupplementLiteralPaths() {
        val current = WearProtocol.commandPath(WearProtocol.CURRENT_VERSION)
        val broad = mapOf("pathPrefix" to "/iz/", "pathPattern" to "/iz/.*",
            "pathAdvancedPattern" to "/iz/.*", "pathSuffix" to "/command")
        for ((attribute, value) in broad) for (keepLiteral in listOf(false, true)) {
            val document = validSource()
            val data = listener(document).children("intent-filter").single().children("data")
                .single { it.android("path") == current }
            if (!keepLiteral) data.removeAttributeNS(ANDROID, "path")
            data.setAttributeNS(ANDROID, "android:$attribute", value)
            assertFalse("Broadened $attribute (literal=$keepLiteral) must fail", violations(document).isEmpty())
        }
        for (path in listOf("$current/extra", "$current-extra", "/iz/v${WearProtocol.CURRENT_VERSION + 1}/command")) {
            val document = validSource()
            val filter = listener(document).children("intent-filter").single()
            val extra = filter.children("data").first().cloneNode(true) as Element
            extra.setAttributeNS(ANDROID, "android:path", path)
            filter.appendChild(extra)
            assertFalse("Unallocated exact path must fail: $path", violations(document).isEmpty())
        }
    }

    @Test fun componentActionSchemeHostAndDuplicateFiltersCannotChangeTheContract() {
        val mutations: List<Pair<String, (Document) -> Unit>> = listOf(
            "component" to { doc -> listener(doc).setAttributeNS(ANDROID, "android:name", ".wear.OtherListener") },
            "action" to { doc -> listener(doc).children("intent-filter").single().children("action").single()
                .setAttributeNS(ANDROID, "android:name", "other.action") },
            "scheme" to { doc -> listener(doc).children("intent-filter").single().children("data").first()
                .setAttributeNS(ANDROID, "android:scheme", "https") },
            "host" to { doc -> listener(doc).children("intent-filter").single().children("data").first()
                .setAttributeNS(ANDROID, "android:host", "one-node") },
            "exported" to { doc -> listener(doc).setAttributeNS(ANDROID, "android:exported", "false") },
            "duplicate filter" to { doc ->
                val service = listener(doc)
                service.appendChild(service.children("intent-filter").single().cloneNode(true))
                Unit
            },
            "duplicate path" to { doc ->
                val filter = listener(doc).children("intent-filter").single()
                filter.appendChild(filter.children("data").first().cloneNode(true))
                Unit
            },
        )
        for ((name, mutate) in mutations) {
            val document = validSource()
            mutate(document)
            assertFalse("Changed $name must fail", violations(document).isEmpty())
        }
    }

    @Test fun sourceManifestLocationSupportsRepositoryAndAppCwdAndFailsClearlyWhenMissing() {
        val source = sourceManifest()
        val app = source.parentFile.parentFile.parentFile
        assertEquals(source, sourceManifest(app))
        assertEquals(source, sourceManifest(app.parentFile))
        val failure = assertThrows(IllegalStateException::class.java) {
            sourceManifest(File(source, "not-a-directory"))
        }
        assertTrue(failure.message.orEmpty().contains("source manifest"))
        assertTrue(failure.message.orEmpty().contains("repository root or app module"))
    }

    private fun validSource(): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val source = sourceManifest()
        val document = factory.newDocumentBuilder().parse(source)
        val errors = violations(document)
        assertTrue("Wear command source manifest contract failed at $source: ${errors.joinToString()}", errors.isEmpty())
        return document
    }

    private fun sourceManifest(cwd: File = File(System.getProperty("user.dir"))): File {
        val candidates = listOfNotNull(File(cwd, "app/src/main/AndroidManifest.xml"),
            File(cwd, "src/main/AndroidManifest.xml").takeIf { cwd.name.equals("app", ignoreCase = true) })
        val found = candidates.filter { it.isFile }
        check(found.size == 1) {
            "Phone source manifest not uniquely found from $cwd; run from repository root or app module. Checked: $candidates"
        }
        return found.single().canonicalFile
    }

    private fun expectedPaths(): Set<String> =
        (1..WearProtocol.CURRENT_VERSION).map { WearProtocol.commandPath(it) }.toSet() + WatchHealthProtocol.PATH

    private fun violations(document: Document): List<String> {
        val errors = mutableListOf<String>()
        val applications = document.documentElement.children("application")
        if (applications.size != 1) return listOf("Expected exactly one application")
        val services = applications.single().children("service").filter { component(it.android("name")) == LISTENER }
        if (services.size != 1) return listOf("Expected exactly one $LISTENER service")
        val service = services.single()
        if (service.android("exported") != "true") errors += "Listener must remain exported"
        val filters = service.children("intent-filter")
        if (filters.size != 1) return errors + "Expected exactly one listener intent filter"
        val filter = filters.single()
        if (filter.children().any { it.tagName !in setOf("action", "data") }) errors += "Unexpected filter element"
        if (filter.children("action").map { it.android("name") } != listOf(MESSAGE)) errors += "Wrong listener action"
        val data = filter.children("data")
        val expected = expectedPaths()
        if (data.size != expected.size || data.map { it.android("path") }.toSet() != expected)
            errors += "Exact command/health path set differs: expected $expected"
        for (entry in data) {
            val attributes = (0 until entry.attributes.length).map { entry.attributes.item(it) }
            if (attributes.size != 3 || attributes.any {
                    it.namespaceURI != ANDROID || it.localName !in setOf("scheme", "host", "path")
                }) errors += "Only exact scheme/host/path attributes are allowed"
            if (entry.android("scheme") != "wear" || entry.android("host") != "*") errors += "Wrong Wear scheme/host"
        }
        return errors
    }

    private fun listener(document: Document): Element = document.documentElement.children("application").single()
        .children("service").single { component(it.android("name")) == LISTENER }
    private fun component(name: String) = if (name.startsWith(".")) "org.iz.navigation$name" else name
    private fun Element.android(name: String): String = getAttributeNS(ANDROID, name)
    private fun Element.children(name: String? = null): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.filter { name == null || it.tagName == name }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val LISTENER = "org.iz.navigation.wear.WearCommandListener"
        const val MESSAGE = "com.google.android.gms.wearable.MESSAGE_RECEIVED"
    }
}
