package dev.ppiankov.pockettag

import java.net.URLEncoder
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID

// WO-3: saved content owns validation and encoding independently of Android.
sealed class TagItem(id: String, label: String) {
    val id: String = id // WO-3: stable identity survives editing and selection.
    val label: String = label.trim() // WO-3: display labels cannot be blank.
    abstract val type: Type // WO-3: persisted discriminator; immutable after creation.

    init {
        require(id.isNotBlank()) { "Item ID is empty." }
        require(this.label.isNotEmpty()) { "Label is empty; not saved." }
    }

    abstract fun ndefMessage(): ByteArray

    // WO-3: migration compares the generated URI before accepting a typed replacement.
    open val uri: String? get() = null

    // WO-3: explicit storage names keep JSON independent of Kotlin class names.
    enum class Type(val storageName: String) {
        LINK("link"), CONTACT("contact"), WHATSAPP("whatsapp"),
        CALL("call"), EMAIL("email"), SMS("sms"), RAW("raw"),
        NOTE("note"), // WO-8: append creatable types without changing saved discriminators.
        PLACE("place"); // WO-9: coordinates use a stable discriminator without a location permission.

        companion object {
            // WO-3: legacy links remain editable but cannot be created from the Add menu.
            val creatableTypes: List<Type> = entries.filterNot { it == RAW }
        }
    }

    // WO-3: web links are restricted to the schemes readers can open as web pages.
    class Link(label: String, url: String, id: String = UUID.randomUUID().toString()) :
        TagItem(id, label) {
        override val type = Type.LINK
        val url: String = url.trim() // WO-3: remove pasted surrounding whitespace before validation.
        override val uri: String get() = url // WO-3: preserve the exact generated URI for migration.
        init {
            require(this.url.startsWith("https://") || this.url.startsWith("http://")) {
                "Web link must start with http:// or https://."
            }
        }
        override fun ndefMessage(): ByteArray = NdefMessage.uriRecord(uri)
    }

    // WO-3: preserve otherwise unrepresentable legacy values without changing what a tap serves.
    class Raw(label: String, uri: String, id: String = UUID.randomUUID().toString()) :
        TagItem(id, label) {
        override val type = Type.RAW
        override val uri: String = uri.trim() // WO-3: the legacy URI is stored without reinterpretation.
        init {
            require(this.uri.isNotEmpty()) { "Link is empty; not saved." }
        }
        override fun ndefMessage(): ByteArray = NdefMessage.uriRecord(uri)
    }

    // WO-3: wa.me requires digits without the international plus sign.
    class WhatsApp(label: String, number: String, id: String = UUID.randomUUID().toString()) :
        TagItem(id, label) {
        override val type = Type.WHATSAPP
        val number: String = phoneNumber(number).removePrefix("+")
        override val uri: String get() = "https://wa.me/$number" // WO-3: canonical chat URI.
        override fun ndefMessage(): ByteArray = NdefMessage.uriRecord(uri)
    }

    // WO-3: telephone links retain a leading plus for international dialling.
    class Call(label: String, number: String, id: String = UUID.randomUUID().toString()) :
        TagItem(id, label) {
        override val type = Type.CALL
        val number: String = phoneNumber(number)
        override val uri: String get() = "tel:$number" // WO-3: exact-match migration candidate.
        override fun ndefMessage(): ByteArray = NdefMessage.uriRecord(uri)
    }

    // WO-3: optional query text is percent-encoded, never appended as URI syntax.
    class Email(
        label: String,
        address: String,
        val subject: String? = null,
        id: String = UUID.randomUUID().toString(),
    ) : TagItem(id, label) {
        override val type = Type.EMAIL
        val address: String = address.trim() // WO-3: trim addresses before validating and storing.
        init {
            require(this.address.count { it == '@' } == 1 &&
                this.address.substringBefore('@').isNotEmpty() && this.address.substringAfter('@').isNotEmpty()) {
                "Email must contain one @ with text on both sides."
            }
        }
        // WO-3: use the same query encoding for migration comparison and the served record.
        override val uri: String get() = "mailto:$address" + query("subject", subject)
        override fun ndefMessage(): ByteArray = NdefMessage.uriRecord(uri)
    }

    // WO-3: SMS uses the same number rules as calls and encodes the optional body.
    class Sms(
        label: String,
        number: String,
        val body: String? = null,
        id: String = UUID.randomUUID().toString(),
    ) : TagItem(id, label) {
        override val type = Type.SMS
        val number: String = phoneNumber(number)
        // WO-3: a typed migration is safe only when this URI matches the legacy text exactly.
        override val uri: String get() = "sms:$number" + query("body", body)
        override fun ndefMessage(): ByteArray = NdefMessage.uriRecord(uri)
    }

    // WO-3: one vCard record carries manually entered contact fields only.
    class Contact(
        label: String,
        val givenName: String = "",
        val familyName: String = "",
        val org: String = "",
        val title: String = "",
        val phone: String = "",
        val email: String = "",
        url: String = "",
        val note: String = "",
        id: String = UUID.randomUUID().toString(),
    ) : TagItem(id, label) {
        override val type = Type.CONTACT
        val url: String = url.trim() // WO-3: URLs are trimmed; the other contact values stay as entered.
        init {
            require(givenName.isNotBlank() || familyName.isNotBlank()) {
                "Enter a given name or family name."
            }
        }

        // WO-3: fixed ordering and CRLF keep the unfolded vCard deterministic.
        override fun ndefMessage(): ByteArray {
            val lines = mutableListOf(
                "BEGIN:VCARD", "VERSION:3.0",
                "N:${escape(familyName)};${escape(givenName)};;;",
                "FN:${escape("$givenName $familyName".trim())}",
            )
            listOf(
                "ORG" to org, "TITLE" to title, "TEL;TYPE=CELL" to phone,
                "EMAIL;TYPE=INTERNET" to email, "URL" to url, "NOTE" to note,
            ).forEach { (name, value) ->
                if (value.isNotEmpty()) lines.add("$name:${escape(value)}")
            }
            lines.add("END:VCARD")
            val payload = lines.joinToString("\r\n", postfix = "\r\n").toByteArray(Charsets.UTF_8)
            return NdefMessage.mimeRecord("text/vcard", payload)
        }
    }

    // WO-8: preserve plain text while validating the language with the shared Text encoder.
    class Note(
        label: String,
        val text: String, // WO-8: multiline UTF-8 content remains exactly as entered.
        language: String = "en",
        id: String = UUID.randomUUID().toString(),
    ) : TagItem(id, label) {
        override val type = Type.NOTE // WO-8: stable JSON discriminator for Text records.
        val language: String = language.trim() // WO-8: pasted language tags may have surrounding whitespace.
        init {
            NdefMessage.textRecord(text, this.language)
        }
        override fun ndefMessage(): ByteArray = NdefMessage.textRecord(text, language)
    }

    // WO-9: manually supplied coordinates become a map link that readers can open on either platform.
    class Place(
        label: String,
        val latitude: Double, // WO-9: validate geographic bounds before encoding or persistence.
        val longitude: Double, // WO-9: the second coordinate cannot be swapped with latitude.
        val name: String = "", // WO-9: retain the optional name even though encoding A uses coordinates only.
        id: String = UUID.randomUUID().toString(),
    ) : TagItem(id, label) {
        override val type = Type.PLACE // WO-9: explicit JSON type for manually entered places.
        init {
            require(latitude in -MAX_LATITUDE..MAX_LATITUDE) { "Latitude must be between -90 and 90." }
            require(longitude in -MAX_LONGITUDE..MAX_LONGITUDE) { "Longitude must be between -180 and 180." }
        }
        // WO-9: use encoding A with deterministic decimal formatting, including on comma locales.
        override val uri: String get() = "https://www.google.com/maps/search/?api=1&query=" +
            coordinate(latitude) + "," + coordinate(longitude)
        override fun ndefMessage(): ByteArray = NdefMessage.uriRecord(uri)

        companion object {
            private const val MAX_LATITUDE = 90.0 // WO-9: geographic latitude bound.
            private const val MAX_LONGITUDE = 180.0 // WO-9: geographic longitude bound.
            private const val COORDINATE_DECIMALS = 6 // WO-9: map URLs use at most six fractional digits.
            private const val LINK_ERROR = "Could not read coordinates from this link" // WO-9: one input error.
            private val QUERY_COORDINATES = Regex("([+-]?[0-9]+(?:\\.[0-9]+)?),\\s*([+-]?[0-9]+(?:\\.[0-9]+)?)") // WO-9: accept a coordinate pair only.
            private val PATH_COORDINATES = Regex("(?:^|/)@([+-]?[0-9]+(?:\\.[0-9]+)?),([+-]?[0-9]+(?:\\.[0-9]+)?)(?:,|/|$)") // WO-9: Google Maps path form includes optional zoom.

            // WO-9: only the two specified Google Maps forms supply coordinates; no redirects are followed.
            fun coordinatesFromLink(link: String): Pair<Double, Double> {
                try {
                    val uri = URI(link.trim())
                    val host = uri.host?.lowercase(Locale.ROOT)
                    require(uri.scheme == "http" || uri.scheme == "https") { LINK_ERROR }
                    require(host in setOf("google.com", "www.google.com", "maps.google.com")) { LINK_ERROR }
                    require(host == "maps.google.com" || uri.path == "/maps" || uri.path.startsWith("/maps/")) {
                        LINK_ERROR
                    }
                    val query = uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "q" }
                    val match = if (query != null) {
                        QUERY_COORDINATES.matchEntire(URLDecoder.decode(query.substringAfter('=', ""), "UTF-8").trim())
                    } else PATH_COORDINATES.find(uri.path)
                    requireNotNull(match) { LINK_ERROR }
                    val latitude = match.groupValues[1].toDouble()
                    val longitude = match.groupValues[2].toDouble()
                    require(latitude in -MAX_LATITUDE..MAX_LATITUDE && longitude in -MAX_LONGITUDE..MAX_LONGITUDE) {
                        LINK_ERROR
                    }
                    return latitude to longitude
                } catch (_: URISyntaxException) {
                    throw IllegalArgumentException(LINK_ERROR)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException(LINK_ERROR)
                }
            }

            // WO-9: strip padding without letting a rounded negative zero become a different map query.
            private fun coordinate(value: Double): String {
                val text = String.format(Locale.ROOT, "%.$COORDINATE_DECIMALS" + "f", value)
                    .trimEnd('0').trimEnd('.')
                return if (text == "-0") "0" else text
            }
        }
    }

    private companion object {
        const val MIN_PHONE_DIGITS = 7 // WO-3: operator-specified input bounds.
        const val MAX_PHONE_DIGITS = 15 // WO-3: operator-specified input bounds.

        // WO-3: strip only specified separators; reject other characters, including Unicode digits.
        fun phoneNumber(value: String): String {
            val number = value.trim().filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }
            val digits = number.removePrefix("+")
            require(digits.length in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS && digits.all { it in '0'..'9' }) {
                "Number must contain 7 to 15 digits, with an optional leading +."
            }
            return number
        }

        private fun query(name: String, value: String?): String =
            if (value.isNullOrEmpty()) "" else "?$name=" +
                URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        // WO-3: escape values only, leaving vCard's structural separators intact.
        fun escape(value: String): String = value.replace("\\", "\\\\")
            .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n")
            .replace(",", "\\,").replace(";", "\\;")
    }
}
