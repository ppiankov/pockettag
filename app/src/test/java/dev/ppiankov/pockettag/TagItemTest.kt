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
        val decoded = ItemJson.decode(json)
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
    fun decodeSkipsMalformedAndUnknownEntriesButRetainsValidNeighbours() {
        val valid = ItemJson.encode(sampleItems()).removePrefix("[").removeSuffix("]")
        val malformed = """null,42,"text",{},
            {"id":"x","type":"future","label":"Unknown"},
            {"id":"x","type":"link","label":"Missing URL"},
            {"id":"x","type":"link","label":"Bad URL","url":"ftp://example.com"},
            {"id":"x","type":"link","label":42,"url":"https://example.com"},
            {"id":"x","type":"sms","label":"Bad body","number":"1234567","body":42},
            {"id":"x","type":"contact","label":"No name"}"""
        assertEquals(6, ItemJson.decode("[$malformed,$valid]").size)
        for (badJson in listOf("not json", "{\"items\":[]}", "[")) {
            assertTrue(ItemJson.decode(badJson).isEmpty())
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
