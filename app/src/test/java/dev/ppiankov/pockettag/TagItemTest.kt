package dev.ppiankov.pockettag

import dev.ppiankov.pockettag.Type4Constants.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TagItemTest {
    @Test
    fun eachUriTypeHasExactBytes() {
        assertArrayEquals(hex("D1010C5504") + bytes("example.com"),
            TagItem.Link("Web", "https://example.com").ndefMessage())
        assertArrayEquals(hex("D101125504") + bytes("wa.me/60123456789"),
            TagItem.WhatsApp("Chat", "+60 12-345 6789").ndefMessage())
        assertArrayEquals(hex("D1010D5505") + bytes("+60123456789"),
            TagItem.Call("Call", "+60 (12)-345 6789").ndefMessage())
        assertArrayEquals(hex("D101215506") + bytes("a@example.com?subject=Hi%20there"),
            TagItem.Email("Email", "a@example.com", "Hi there").ndefMessage())
        assertArrayEquals(hex("D101215500") + bytes("sms:+60123456789?body=Hi%20there"),
            TagItem.Sms("SMS", "+60 12-345 6789", "Hi there").ndefMessage())
    }

    @Test
    fun fullVcardHasExactUtf8EscapesAndLineEndings() {
        val item = TagItem.Contact("Contact", "Paweł", "Example", "Example, Inc", "Lead; engineer",
            "+60 12-345 6789", "a@example.com", "https://example.com", "Line 1\\path\nLine 2")
        val expected = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Example;Paweł;;;\r\n" +
            "FN:Paweł Example\r\nORG:Example\\, Inc\r\nTITLE:Lead\\; engineer\r\n" +
            "TEL;TYPE=CELL:+60 12-345 6789\r\nEMAIL;TYPE=INTERNET:a@example.com\r\n" +
            "URL:https://example.com\r\nNOTE:Line 1\\\\path\\nLine 2\r\nEND:VCARD\r\n"
        assertArrayEquals(hex("D20AED") + bytes("text/vcard") + bytes(expected), item.ndefMessage())
    }

    @Test
    fun vcardOmitsEmptyFieldsAndEscapesStructuredNamesWithoutFolding() {
        val minimal = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:;Ada;;;\r\nFN:Ada\r\nEND:VCARD\r\n"
        assertArrayEquals(hex("D20A38") + bytes("text/vcard") + bytes(minimal),
            TagItem.Contact("Contact", givenName = "Ada").ndefMessage())
        val card = TagItem.Contact("Contact", givenName = "A,B;C\\D\nE",
            note = "a".repeat(90) + "\r\nb\rc").ndefMessage().toString(Charsets.UTF_8)
        assertTrue(card.contains("N:;A\\,B\\;C\\\\D\\nE;;;\r\n"))
        assertTrue(card.contains("FN:A\\,B\\;C\\\\D\\nE\r\n"))
        assertTrue(card.contains("NOTE:" + "a".repeat(90) + "\\nb\\nc\r\n"))
        assertFalse(card.contains("\r\n "))
    }

    @Test
    fun identityAndLabelsAreValidatedAtCreation() {
        val first = TagItem.Link("  Web link  ", "https://example.com")
        assertEquals("Web link", first.label)
        assertEquals(first.id, UUID.fromString(first.id).toString())
        assertThrows(IllegalArgumentException::class.java) { TagItem.Call(" ", "1234567") }
        assertThrows(IllegalArgumentException::class.java) { TagItem.Link("Web", "example.com") }
        assertThrows(IllegalArgumentException::class.java) { TagItem.Link("Web", "ftp://example.com") }
        assertThrows(IllegalArgumentException::class.java) { TagItem.Contact("Contact") }
        assertThrows(IllegalArgumentException::class.java) { TagItem.Contact("Contact", " ", " ") }
        TagItem.Link("Web", "http://example.com")
        TagItem.Contact("Contact", familyName = "Example")
    }

    @Test
    fun allPhoneTypesEnforceAsciiDigitsAndBothBoundaries() {
        for (valid in listOf("1234567", "+123456789012345", "+60 (12)-345 6789")) {
            TagItem.WhatsApp("Chat", valid)
            TagItem.Call("Call", valid)
            TagItem.Sms("SMS", valid)
        }
        for (invalid in listOf("123456", "1234567890123456", "++1234567", "12+34567",
            "123456x", "１２３４５６７", "123\t4567", "123.4567", "+")) {
            assertThrows(IllegalArgumentException::class.java) { TagItem.WhatsApp("Chat", invalid) }
            assertThrows(IllegalArgumentException::class.java) { TagItem.Call("Call", invalid) }
            assertThrows(IllegalArgumentException::class.java) { TagItem.Sms("SMS", invalid) }
        }
    }

    @Test
    fun emailValidationAndOptionalQueryEncoding() {
        for (invalid in listOf("", "example.com", "@example.com", "a@", "a@@example.com")) {
            assertThrows(IllegalArgumentException::class.java) { TagItem.Email("Email", invalid) }
        }
        for (optional in listOf(null, "")) {
            assertArrayEquals(hex("D1010E5506") + bytes("a@example.com"),
                TagItem.Email("Email", "a@example.com", optional).ndefMessage())
            assertArrayEquals(hex("D101115500") + bytes("sms:+60123456789"),
                TagItem.Sms("SMS", "+60123456789", optional).ndefMessage())
        }
        val encoded = "a%2Bb%26c%3F%3D%20%C5%82%0A"
        assertTrue(TagItem.Email("Email", "a@example.com", "a+b&c?= ł\n")
            .ndefMessage().toString(Charsets.UTF_8).endsWith("?subject=$encoded"))
        assertTrue(TagItem.Sms("SMS", "1234567", "a+b&c?= ł\n")
            .ndefMessage().toString(Charsets.UTF_8).endsWith("?body=$encoded"))
    }

    @Test
    fun mimeShortAndLongLengthBoundariesAreExact() {
        val short = ByteArray(255) { 0x61 }
        val long = ByteArray(256) { 0x62 }
        assertArrayEquals(hex("D20AFF") + bytes("text/vcard") + short,
            NdefMessage.mimeRecord("text/vcard", short))
        assertArrayEquals(hex("C20A00000100") + bytes("text/vcard") + long,
            NdefMessage.mimeRecord("text/vcard", long))
    }

    @Test
    fun uriLongFormStartsAt256PayloadBytes() {
        assertArrayEquals(hex("D101FF5504") + bytes("a".repeat(254)),
            NdefMessage.uriRecord("https://" + "a".repeat(254)))
        assertArrayEquals(hex("C101000001005504") + bytes("a".repeat(255)),
            NdefMessage.uriRecord("https://" + "a".repeat(255)))
    }

    @Test
    fun fileLimitIncludesTheTwoNlenBytes() {
        val message = ByteArray(1022) { 0x61 }
        assertArrayEquals(hex("03FE") + message, NdefMessage.ndefFile(message))
        assertThrows(NdefTooLargeException::class.java) { NdefMessage.ndefFile(ByteArray(1023)) }
    }

    @Test
    fun largeFileIsReassembledAcrossAdvertisedReadChunks() {
        val file = NdefMessage.ndefFile(ByteArray(598) { (it % 251).toByte() })
        val tag = Type4Tag { file }
        assertEquals(600, file.size)
        assertArrayEquals(hex("9000"), tag.process(hex("00A4040007D276000085010100")))
        assertArrayEquals(hex("9000"), tag.process(hex("00A4000C02E104")))
        var received = byteArrayOf()
        for (offset in file.indices step 0x3B) {
            val response = tag.process(byteArrayOf(0, 0xB0.toByte(),
                (offset ushr 8).toByte(), offset.toByte(), 0x3B))
            assertArrayEquals(hex("9000"), response.takeLast(2).toByteArray())
            received += response.dropLast(2).toByteArray()
        }
        assertArrayEquals(file, received)
    }

    @Test
    fun jsonRoundTripsEveryTypeAndOptionalValues() {
        val items = sampleItems() + listOf(TagItem.Email("No subject", "b@example.com"),
            TagItem.Sms("No body", "1234567"), TagItem.Contact("Minimal", familyName = "Example"))
        val json = ItemJson.encode(items)
        val decoded = ItemJson.decode(json).items
        assertEquals(items.size, decoded.size)
        assertEquals(json, ItemJson.encode(decoded))
        items.zip(decoded).forEach { (before, after) ->
            assertEquals(before.id, after.id)
            assertEquals(before.label, after.label)
            assertEquals(before.type, after.type)
            assertArrayEquals(before.ndefMessage(), after.ndefMessage())
        }
    }

    @Test
    fun decodeHidesMalformedAndUnknownEntriesButRetainsValidNeighbours() {
        val valid = ItemJson.encode(sampleItems()).removePrefix("[").removeSuffix("]")
        val malformed = """null,42,"text",{},
            {"id":"x","type":"future","label":"Unknown"},
            {"id":"x","type":"link","label":"Missing URL"},
            {"id":"x","type":"link","label":"Bad URL","url":"ftp://example.com"},
            {"id":"x","type":"link","label":42,"url":"https://example.com"},
            {"id":"x","type":"sms","label":"Bad body","number":"1234567","body":42},
            {"id":"x","type":"contact","label":"No name"}"""
        assertEquals(6, ItemJson.decode("[$malformed,$valid]").items.size)
        for (badJson in listOf("not json", "{\"items\":[]}", "[")) {
            assertTrue(ItemJson.decode(badJson).items.isEmpty())
        }
    }

    @Test
    fun firstLoadMigratesTheLegacyUrlOrUsesTheDefault() {
        val legacy = ItemState.load(null, null, "https://example.com/old")
        assertEquals(1, legacy.items.size)
        assertEquals("Web link", legacy.activeItem?.label)
        assertEquals("https://example.com/old", (legacy.activeItem as TagItem.Link).url)
        assertEquals(legacy.items.first().id, legacy.activeItemId)
        val empty = ItemState.load(null, null, null)
        assertEquals(TagPrefs.DEFAULT_URL, (empty.activeItem as TagItem.Link).url)
        assertEquals("Web link", empty.activeItem?.label)
    }

    @Test
    fun existingItemsAndAnIntentionalEmptyListDoNotMigrateAgain() {
        val items = sampleItems()
        val state = ItemState.load(ItemJson.encode(items), items[2].id, "https://example.com/old")
        assertEquals(items[2].id, state.activeItemId)
        assertEquals(6, state.items.size)
        val empty = ItemState.load("[]", items[2].id, "https://example.com/old")
        assertTrue(empty.items.isEmpty())
        assertNull(empty.activeItemId)
        assertNull(ItemState.load(ItemJson.encode(items), "missing", null).activeItem)
    }

    @Test
    fun deleteSelectsFirstRemainingOnlyWhenTheActiveItemIsRemoved() {
        val items = sampleItems()
        val state = ItemState(items, items[2].id)
        assertEquals(items[0].id, state.delete(items[2].id).activeItemId)
        assertEquals(items[2].id, state.delete(items[0].id).activeItemId)
        assertEquals(items.drop(1).map { it.id }, state.delete(items[0].id).items.map { it.id })
        val last = ItemState(listOf(items[0]), items[0].id).delete(items[0].id)
        assertTrue(last.items.isEmpty())
        assertNull(last.activeItemId)
    }

    @Test
    fun saveAndSelectPreserveOrderAndImmutableType() {
        val items = sampleItems()
        val selected = ItemState(items, items[0].id).select(items[1].id)
        val edited = TagItem.Link("Edited", "https://example.com/new", items[0].id)
        val saved = selected.save(edited)
        assertEquals(items.map { it.id }, saved.items.map { it.id })
        assertEquals(items[1].id, saved.activeItemId)
        assertEquals("Edited", saved.items[0].label)
        assertThrows(IllegalArgumentException::class.java) {
            saved.save(TagItem.Call("Changed type", "1234567", items[0].id))
        }
        assertThrows(IllegalArgumentException::class.java) { saved.select("missing") }
        assertEquals(edited.id, ItemState(emptyList(), null).save(edited).activeItemId)
        val added = saved.save(TagItem.Link("Another", "https://example.com/another"))
        assertEquals(7, added.items.size)
        assertEquals(saved.activeItemId, added.activeItemId)
    }

    @Test
    fun providerHidesDisabledEmptyAndOversizedContentAndChangesOnNextSelect() {
        val items = sampleItems()
        var state = ItemState(items, items[0].id)
        var enabled = true
        val tag = Type4Tag { state.ndefFile(enabled) }
        val select = hex("00A4040007D276000085010100")
        assertArrayEquals(hex("9000"), tag.process(select))
        state = state.select(items[3].id)
        assertArrayEquals(hex("9000"), tag.process(select))
        tag.process(hex("00A4000C02E104"))
        assertArrayEquals(NdefMessage.ndefFile(items[3].ndefMessage()) + hex("9000"),
            tag.process(hex("00B00000FF")))
        enabled = false
        assertArrayEquals(hex("6A82"), tag.process(select))
        enabled = true
        state = ItemState(emptyList(), null)
        assertArrayEquals(hex("6A82"), tag.process(select))
        val huge = TagItem.Link("Huge", "https://example.com/" + "a".repeat(1024))
        state = ItemState(listOf(huge), huge.id)
        assertArrayEquals(hex("6A82"), tag.process(select))
    }

    @Test
    fun migrationMapsSchemesOnlyWhenTheGeneratedUriIsExact() {
        val cases = listOf(
            Triple("https://example.com", TagItem.Type.LINK, "Web link"),
            Triple("tel:+60123456789", TagItem.Type.CALL, "Phone"),
            Triple("mailto:a@example.com", TagItem.Type.EMAIL, "Email"),
            Triple("mailto:a@example.com?subject=Hi%20there", TagItem.Type.EMAIL, "Email"),
            Triple("mailto:a@example.com?subject=Hi+there", TagItem.Type.RAW, "Saved link"),
            Triple("mailto:a@example.com?cc=b@example.com", TagItem.Type.RAW, "Saved link"),
            Triple("sms:+60123456789?body=Hello", TagItem.Type.SMS, "SMS"),
            Triple("tel:+60 12-345 6789", TagItem.Type.RAW, "Saved link"),
            Triple("geo:1,2", TagItem.Type.RAW, "Saved link"),
            Triple("example.com", TagItem.Type.RAW, "Saved link"),
            Triple("https://" + "a".repeat(292), TagItem.Type.LINK, "Web link"),
            Triple(null, TagItem.Type.LINK, "Web link"),
            Triple("", TagItem.Type.LINK, "Web link"),
        )
        for ((legacy, type, label) in cases) {
            val state = ItemState.load(null, null, legacy)
            val item = requireNotNull(state.activeItem)
            val original = legacy?.takeIf { it.isNotEmpty() } ?: TagPrefs.DEFAULT_URL
            assertEquals(type, item.type)
            assertEquals(label, item.label)
            assertEquals(item.id, state.activeItemId)
            assertArrayEquals(NdefMessage.ndefFile(original), NdefMessage.ndefFile(item.ndefMessage()))
            if (legacy == "mailto:a@example.com?subject=Hi%20there") {
                assertEquals("Hi there", (item as TagItem.Email).subject)
            }
            if (legacy?.length == 300) assertEquals(0xC1.toByte(), item.ndefMessage()[0])
        }
    }

    @Test
    fun migrationNeverThrowsForThirtyAdversarialNonemptyValues() {
        val adversarial = listOf(
            "\u0000", "mailto:", "tel:", "sms:?body=", "こんにちは", "x".repeat(1100), "%",
            "mailto:a@example.com?subject=%", "mailto:a@example.com?subject=%GG",
            "mailto:a@example.com?subject=%C3", "mailto:a@example.com?subject=a+b",
            "mailto:a@example.com?cc=b@example.com", "mailto:a@example.com?subject=a&cc=b@example.com",
            "mailto:@", "mailto:a@@example.com", "tel:+", "tel:++60123456789",
            "tel:+60 12-345 6789", "tel:１２３４５６７", "tel:123\t4567", "sms:",
            "sms:1234567?body=%", "sms:1234567?body=%GG", "sms:1234567?body=a&x=b",
            "sms:1234567?body=", "geo:1,2", "example.com", "HTTP://example.com", "\uD800", " \t\n",
        )
        assertEquals(30, adversarial.size)
        for (legacy in adversarial) {
            assertTrue(legacy.isNotEmpty())
            val state = ItemState.load(null, null, legacy)
            assertEquals(1, state.items.size)
            assertEquals(state.items.single().id, state.activeItemId)
        }
    }

    @Test
    fun rawRoundTripsButIsNotOfferedForCreationAndEditsTrimItsText() {
        val raw = TagItem.Raw("Saved link", "  geo:1,2  ")
        val json = ItemJson.encode(listOf(raw))
        val restored = ItemJson.decode(json).items.single() as TagItem.Raw
        assertEquals("raw", restored.type.storageName)
        assertEquals("geo:1,2", restored.uri)
        assertEquals(raw.id, restored.id)
        assertArrayEquals(NdefMessage.ndefFile("geo:1,2"), NdefMessage.ndefFile(restored.ndefMessage()))
        assertEquals(json, ItemJson.encode(listOf(restored)))
        assertEquals(listOf(TagItem.Type.LINK, TagItem.Type.CONTACT, TagItem.Type.WHATSAPP,
            TagItem.Type.CALL, TagItem.Type.EMAIL, TagItem.Type.SMS), TagItem.Type.creatableTypes)
        assertFalse(TagItem.Type.creatableTypes.contains(TagItem.Type.RAW))
        val edited = ItemState(listOf(raw), raw.id)
            .save(TagItem.Raw(raw.label, " \texample.com\n", raw.id)).activeItem as TagItem.Raw
        assertEquals("example.com", edited.uri)
        assertEquals("Saved link", edited.label)
        assertThrows(IllegalArgumentException::class.java) { TagItem.Raw("Saved link", " \t\n") }
    }

    @Test
    fun unreadableEntriesSurviveSelectionEditingAndDeletionByteIdentically() {
        val first = TagItem.Link("First", "https://example.com/first", "first")
        val second = TagItem.Link("Second", "https://example.com/second", "second")
        val unknown = """{ "id":"future", "type":"future", "label":"Later", "nested":[{"text":"},]"}], "number":1.2300, "escaped":"\u0061" }"""
        val wrongType = """{ "id":42, "type":"link", "label":"Wrong ID", "url":"https://example.com" }"""
        val invalid = """{ "id":"invalid", "type":"link", "label":"Invalid", "url":"ftp://example.com" }"""
        val firstJson = ItemJson.encode(listOf(first)).removePrefix("[").removeSuffix("]")
        val secondJson = ItemJson.encode(listOf(second)).removePrefix("[").removeSuffix("]")
        val original = "[$unknown,$firstJson,$wrongType,$secondJson,$invalid]"
        val loaded = ItemState.load(original, first.id, null)
        assertEquals(listOf(first.id, second.id), loaded.items.map { it.id })
        assertEquals(original, ItemJson.encode(loaded.document))
        val selected = loaded.select(second.id)
        assertEquals(original, ItemJson.encode(selected.document))
        val edited = TagItem.Link("Edited", "https://example.com/edited", first.id)
        val editedJson = ItemJson.encode(listOf(edited)).removePrefix("[").removeSuffix("]")
        val saved = selected.save(edited)
        assertEquals("[$unknown,$editedJson,$wrongType,$secondJson,$invalid]", ItemJson.encode(saved.document))
        val deleted = saved.delete(second.id)
        assertEquals("[$unknown,$editedJson,$wrongType,$invalid]", ItemJson.encode(deleted.document))
        assertEquals(first.id, deleted.activeItemId)
        assertEquals(listOf(first.id), ItemJson.decode(ItemJson.encode(deleted.document)).items.map { it.id })
        val added = deleted.save(second)
        assertEquals("[$unknown,$editedJson,$wrongType,$invalid,$secondJson]", ItemJson.encode(added.document))
    }

    @Test
    fun unreadableValuesAndEscapedDelimitersArePreservedWithoutBeingServed() {
        val json = """[null,42,"text",[1,{"text":",]}"}],{"type":"future","text":"a\\\"},[b"}]"""
        val state = ItemState.load(json, "future", null)
        assertTrue(state.items.isEmpty())
        assertNull(state.activeItemId)
        assertNull(state.ndefFile(true))
        assertEquals(json, ItemJson.encode(state.document))
    }

    @Test
    fun pastedFieldsAreTrimmedWithoutChangingFreeText() {
        assertEquals("https://example.com", TagItem.Link("Web", " \thttps://example.com\n").url)
        assertEquals("a@example.com", TagItem.Email("Email", " \ta@example.com\n", " Subject ").address)
        assertEquals(" Subject ", TagItem.Email("Email", "a@example.com", " Subject ").subject)
        assertEquals("60123456789", TagItem.WhatsApp("Chat", "\t +60 12-345 6789 \n").number)
        assertEquals("+60123456789", TagItem.Call("Phone", "\t +60 12-345 6789 \n").number)
        val sms = TagItem.Sms("SMS", "\t +60 12-345 6789 \n", " Body \n")
        assertEquals("+60123456789", sms.number)
        assertEquals(" Body \n", sms.body)
        val contact = TagItem.Contact("Contact", " Given ", " Family ", " Org ", " Title ",
            " Phone ", " Email ", " \thttps://example.com\n", " Note \n")
        assertEquals("https://example.com", contact.url)
        assertEquals(" Given ", contact.givenName)
        assertEquals(" Family ", contact.familyName)
        assertEquals(" Org ", contact.org)
        assertEquals(" Title ", contact.title)
        assertEquals(" Phone ", contact.phone)
        assertEquals(" Email ", contact.email)
        assertEquals(" Note \n", contact.note)
        assertEquals("https://example.com", ItemState.load(null, null, " \thttps://example.com\n").activeItem?.uri)
    }

    private fun sampleItems(): List<TagItem> = listOf(
        TagItem.Link("Web", "https://example.com"),
        TagItem.WhatsApp("Chat", "+60 12-345 6789"),
        TagItem.Call("Call", "+60 12-345 6789"),
        TagItem.Email("Email", "a@example.com", "Hi there"),
        TagItem.Sms("SMS", "+60 12-345 6789", "Hi there"),
        TagItem.Contact("Contact", "Paweł", "Example", "Example, Inc", "Engineer",
            "+60 12-345 6789", "a@example.com", "https://example.com", "Line 1\nLine 2"),
    )

    private fun bytes(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
}
