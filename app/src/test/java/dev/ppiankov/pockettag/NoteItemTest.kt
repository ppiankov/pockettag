package dev.ppiankov.pockettag

import dev.ppiankov.pockettag.Type4Constants.hex
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// WO-8: text records and persistence are tested without changing the six original fixtures.
class NoteItemTest {
    @Test
    fun helloUsesUtf8TextWithDefaultEnglishLanguage() {
        val note = TagItem.Note("Note", "Hello")
        assertEquals("note", note.type.storageName)
        assertEquals("en", note.language)
        assertArrayEquals(hex("D101085402656E48656C6C6F"), note.ndefMessage())
    }

    @Test
    fun multilineEmojiUsesUtf8BytesAndPreservesFreeText() {
        assertArrayEquals(hex("D1010A5402656E48690AF09F9880"),
            TagItem.Note("Note", "Hi\n😀").ndefMessage())
        val text = "  Line one\nLine two  "
        assertEquals(text, TagItem.Note("Note", text).text)
        assertEquals(" \n", TagItem.Note("Note", " \n").text)
    }

    @Test
    fun longTextUsesFourByteLengthAndLeavesUtf8FlagClear() {
        val text = "a".repeat(253)
        assertArrayEquals(hex("C101000001005402656E") + text.toByteArray(Charsets.UTF_8),
            TagItem.Note("Note", text).ndefMessage())
    }

    @Test
    fun wholeFileLimitIncludesRecordAndLanguageOverhead() {
        val largest = TagItem.Note("Note", "a".repeat(1012))
        assertEquals(1024, NdefMessage.ndefFile(largest.ndefMessage()).size)
        val error = assertThrows(NdefTooLargeException::class.java) {
            NdefMessage.ndefFile(TagItem.Note("Note", "a".repeat(1013)).ndefMessage())
        }
        assertEquals("Item exceeds the 1024-byte tag file limit.", error.message)
    }

    @Test
    fun languageAllowsBcp47AndKeepsTheEnteredTag() {
        val note = TagItem.Note("Note", "Hello", " zh-Hant-TW ")
        assertEquals("zh-Hant-TW", note.language)
        assertArrayEquals(hex("D10110540A") + "zh-Hant-TWHello".toByteArray(Charsets.UTF_8),
            note.ndefMessage())
        val longest = "x-" + List(6) { "abcdefgh" }.joinToString("-") + "-abcdefg"
        assertEquals(63, longest.length)
        assertEquals(63, TagItem.Note("Note", "a", longest).ndefMessage()[4].toInt())
    }

    @Test
    fun emptyTextAndInvalidLanguageAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TagItem.Note("Note", "") }
        listOf("", "e", "en-", "中文", "en_US", "x-" + List(7) { "abcdefgh" }.joinToString("-"))
            .forEach { language ->
                assertThrows(IllegalArgumentException::class.java) { TagItem.Note("Note", "a", language) }
            }
    }

    @Test
    fun jsonRoundTripKeepsIdentityLabelTextAndLanguage() {
        val note = TagItem.Note("Example note", "First\nSecond 😀", "de", "note-id")
        val json = ItemJson.encode(listOf(note))
        val restored = ItemJson.decode(json).items.single() as TagItem.Note
        assertEquals(note.id, restored.id)
        assertEquals(note.label, restored.label)
        assertEquals(note.text, restored.text)
        assertEquals(note.language, restored.language)
        assertArrayEquals(note.ndefMessage(), restored.ndefMessage())
        assertEquals(json, ItemJson.encode(listOf(restored)))
    }

    @Test
    fun missingStoredLanguageUsesEnglish() {
        val document = ItemJson.decode("""[{"id":"note-id","type":"note","label":"Note","text":"Hello"}]""")
        assertEquals("en", (document.items.single() as TagItem.Note).language)
    }

    @Test
    fun preClusterJsonStillLoadsUnchanged() {
        val state = ItemState.load(PRE_CLUSTER_JSON, "old-link", null)
        assertTrue(state.readable)
        assertEquals(listOf("link", "contact", "whatsapp", "call", "email", "sms", "raw"),
            state.items.map { it.type.storageName })
        assertEquals("old-link", state.activeItemId)
        assertEquals("https://example.com/", state.activeItem?.uri)
        assertTrue(JSONArray(PRE_CLUSTER_JSON).similar(JSONArray(ItemJson.encode(state.document))))
    }
}
