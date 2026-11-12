package org.iz.navigation.placeinfo

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object PublicPlaceMapper {
    fun map(snapshot: PublicPlaceSnapshot): PublicPlacePresentation {
        val tags = snapshot.tags
        fun text(key: String) = tags[key]?.trim()?.takeIf(String::isNotEmpty)
        val phones = contacts(tags, listOf("phone", "contact:phone"), ::phoneUri) { it.split(';') }
        val websites = contacts(tags, listOf("website", "contact:website"), ::websiteUri, ::websiteValues)
        val street = listOfNotNull(text("addr:street"), text("addr:housenumber")).joinToString(" ")
        val city = listOfNotNull(text("addr:postcode"), text("addr:city")).joinToString(" ")
        val address = text("addr:full") ?: listOf(street, city).filter(String::isNotBlank).joinToString(", ").takeIf(String::isNotEmpty)
        val wheel = text("wheelchair")
        return PublicPlacePresentation(text("name"), address, phones.first, websites.first,
            phones.second, websites.second, when (wheel) {
                null -> PlaceTagValue.MISSING
                "yes" -> PlaceTagValue.YES
                "no" -> PlaceTagValue.NO
                "limited" -> PlaceTagValue.LIMITED
                "designated" -> PlaceTagValue.DESIGNATED
                else -> PlaceTagValue.UNKNOWN
            }, wheel, tags.entries.filter { it.key.startsWith("payment:") }.sortedBy { it.key }.map { (key, raw) ->
                PlacePaymentFact(key, paymentNames[key] ?: key, when (raw.trim()) {
                    "yes" -> PlaceTagValue.YES
                    "no" -> PlaceTagValue.NO
                    "only" -> PlaceTagValue.ONLY
                    else -> PlaceTagValue.UNKNOWN
                }, raw)
            }, text("opening_hours"), text("timezone"))
    }

    private fun contacts(tags: Map<String, String>, keys: List<String>, uri: (String) -> String?,
        split: (String) -> List<String>): Pair<List<PlaceInfoAction>, List<String>> {
        val actions = linkedMapOf<String, PlaceInfoAction>()
        val invalid = linkedSetOf<String>()
        for (key in keys) for (value in split(tags[key].orEmpty()).map(String::trim).filter(String::isNotEmpty)) {
            val safe = uri(value)
            if (safe == null) invalid += value
            else actions[safe] = actions[safe]?.let { it.copy(sourceKeys = it.sourceKeys + key) }
                ?: PlaceInfoAction(value, safe, setOf(key))
        }
        return actions.values.toList() to invalid.toList()
    }

    private fun phoneUri(raw: String): String? {
        if (raw.length > 100 || raw.any { it !in "+0123456789 ()-." }) return null
        val number = raw.filterNot { it in " ()-." }
        if (!Regex("\\+?[0-9]{3,20}").matches(number)) return null
        return "tel:$number"
    }

    private fun websiteUri(raw: String): String? {
        if (raw.length > 2048 || raw.any { it.isWhitespace() || it.code < 32 || it.code == 127 || it == '\\' } ||
            !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return url.toString()
    }

    private fun websiteValues(raw: String): List<String> {
        // Literal spaces cannot belong to a valid URL. Keep semicolons in a valid single
        // path/query; split only an explicit spaced URL list or an otherwise invalid whole.
        val explicit = raw.split(Regex("; +(?=https?://)", RegexOption.IGNORE_CASE))
        if (explicit.size > 1) return explicit
        if (websiteUri(raw.trim()) != null) return listOf(raw)
        return raw.split(';')
    }

    private val paymentNames = mapOf("payment:cash" to "Nakit", "payment:credit_cards" to "Kredi kartı",
        "payment:debit_cards" to "Banka kartı", "payment:contactless" to "Temassız", "payment:visa" to "Visa",
        "payment:mastercard" to "Mastercard", "payment:maestro" to "Maestro", "payment:american_express" to "American Express",
        "payment:diners_club" to "Diners Club", "payment:discover" to "Discover", "payment:unionpay" to "UnionPay", "payment:jcb" to "JCB")
}
