package dev.ppiankov.pockettag

import java.net.URLEncoder
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

    // WO-3: explicit storage names keep JSON independent of Kotlin class names.
    enum class Type(val storageName: String) {
        LINK("link"), CONTACT("contact"), WHATSAPP("whatsapp"),
        CALL("call"), EMAIL("email"), SMS("sms");
    }

    // WO-3: web links are restricted to the schemes readers can open as web pages.
    class Link(label: String, val url: String, id: String = UUID.randomUUID().toString()) :
        TagItem(id, label) {
        override val type = Type.LINK
        init {
            require(url.startsWith("https://") || url.startsWith("http://")) {
                "Web link must start with http:// or https://."
            }
        }
        override fun ndefMessage(): ByteArray = NdefMessage.uriRecord(url)
    }

    // WO-3: wa.me requires digits without the international plus sign.
    class WhatsApp(label: String, number: String, id: String = UUID.randomUUID().toString()) :
        TagItem(id, label) {
        override val type = Type.WHATSAPP
        val number: String = phoneNumber(number).removePrefix("+")
        override fun ndefMessage(): ByteArray = NdefMessage.uriRecord("https://wa.me/$number")
    }

    // WO-3: telephone links retain a leading plus for international dialling.
    class Call(label: String, number: String, id: String = UUID.randomUUID().toString()) :
        TagItem(id, label) {
        override val type = Type.CALL
        val number: String = phoneNumber(number)
        override fun ndefMessage(): ByteArray = NdefMessage.uriRecord("tel:$number")
    }

    // WO-3: optional query text is percent-encoded, never appended as URI syntax.
    class Email(
        label: String,
        val address: String,
        val subject: String? = null,
        id: String = UUID.randomUUID().toString(),
    ) : TagItem(id, label) {
        override val type = Type.EMAIL
        init {
            require(address.count { it == '@' } == 1 &&
                address.substringBefore('@').isNotEmpty() && address.substringAfter('@').isNotEmpty()) {
                "Email must contain one @ with text on both sides."
            }
        }
        override fun ndefMessage(): ByteArray =
            NdefMessage.uriRecord("mailto:$address" + query("subject", subject))
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
        override fun ndefMessage(): ByteArray = NdefMessage.uriRecord("sms:$number" + query("body", body))
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
        val url: String = "",
        val note: String = "",
        id: String = UUID.randomUUID().toString(),
    ) : TagItem(id, label) {
        override val type = Type.CONTACT
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

    private companion object {
        const val MIN_PHONE_DIGITS = 7 // WO-3: operator-specified input bounds.
        const val MAX_PHONE_DIGITS = 15 // WO-3: operator-specified input bounds.

        // WO-3: strip only specified separators; reject other characters, including Unicode digits.
        fun phoneNumber(value: String): String {
            val number = value.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }
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
