package dev.ppiankov.pockettag

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

// WO-21: shared drafts and the exported entry point are checked without Android UI or storage writes.
class ShareReceiverTest {
    // WO-26: web shares retain their entered text until the link is saved.
    @Test
    fun uppercaseAndMixedCaseWebSharesKeepEnteredContent() {
        listOf("HTTPS://example.com/", "Http://example.com/", "HTTPS://Example.com/Path?Q=1").forEach { text ->
            val draft = sharedItemDraft(text)
            assertEquals(TagItem.Type.LINK, draft.type)
            assertEquals(text, draft.content)
            assertEquals(text, draft.label)
        }
    }

    // WO-26: canonical schemes preserve host, path, query, and fragment casing and use URI compression.
    @Test
    fun uppercaseLinksNormalizeOnlyTheSchemeAndUseTheHttpsPrefix() {
        listOf("HTTPS://example.com/A" to "https://example.com/A",
            " HTTPS://Example.com/Path?Q=1#Part " to "https://Example.com/Path?Q=1#Part")
            .forEach { (entered, expected) ->
                val link = TagItem.Link("x", entered)
                assertEquals(expected, link.url)
                assertEquals(expected, link.uri)
                assertEquals(0x04.toByte(), NdefMessage.uriPayload(link.uri).first())
                assertArrayEquals(TagItem.Link("x", expected).ndefMessage(), link.ndefMessage())
            }
    }

    // WO-26: a mixed-case HTTP scheme uses the standard HTTP prefix without lowercasing the remainder.
    @Test
    fun mixedCaseHttpLinksUseTheHttpPrefix() {
        val link = TagItem.Link("x", "Http://Example.com/A?Q=1")
        assertEquals("http://Example.com/A?Q=1", link.url)
        assertEquals(0x03.toByte(), NdefMessage.uriPayload(link.uri).first())
        assertArrayEquals(TagItem.Link("x", "http://Example.com/A?Q=1").ndefMessage(), link.ndefMessage())
    }

    // WO-26: the JSON document persists the canonical scheme and preserves the rest across reloads.
    @Test
    fun uppercaseEnteredLinkJsonRoundTripKeepsTheCanonicalScheme() {
        val expected = "https://Example.com/Path?Q=1#Part"
        val link = TagItem.Link("Web", "HTTPS://Example.com/Path?Q=1#Part", "web-id")
        val json = ItemJson.encode(listOf(link))
        assertEquals(expected, JSONArray(json).getJSONObject(0).getString("url"))
        val restored = ItemJson.decode(json).items.single() as TagItem.Link
        assertEquals(expected, restored.url)
        assertEquals(link.id, restored.id)
        assertEquals(link.label, restored.label)
        assertArrayEquals(link.ndefMessage(), restored.ndefMessage())
        assertEquals(json, ItemJson.encode(listOf(restored)))
    }

    // WO-26: accepting web scheme casing does not widen the allowed schemes or prefix syntax.
    @Test
    fun nonWebSchemesRemainNotesAndCannotBecomeLinks() {
        listOf("ftp://example.com/file", "FTP://example.com/file", "mailto:a@example.com",
            "MAILTO:a@example.com", "geo:1,2", "GEO:1,2", "http:example.com", "https:/example.com", "http\u017F://example.com")
            .forEach { text ->
                val draft = sharedItemDraft(text)
                assertEquals(TagItem.Type.NOTE, draft.type)
                assertEquals(text, draft.content)
                val error = assertThrows(IllegalArgumentException::class.java) { TagItem.Link("x", text) }
                assertEquals("Web link must start with http:// or https://.", error.message)
            }
    }

    // WO-26: legacy uppercase schemes remain Saved links with their original type and served bytes.
    @Test
    fun legacyUppercaseWebSchemesStayRawWithExactBytes() {
        listOf("HTTP://example.com", "HTTPS://Example.com/A?Q=1", "Http://example.com/").forEach { legacy ->
            val state = ItemState.load(null, null, legacy)
            val item = requireNotNull(state.activeItem)
            assertEquals(TagItem.Type.RAW, item.type)
            assertEquals("Saved link", item.label)
            assertEquals(legacy, item.uri)
            assertArrayEquals(NdefMessage.ndefFile(legacy), state.ndefFile(true))
        }
    }

    @Test
    fun bareHttpsUrlBecomesAWebLink() {
        val draft = sharedItemDraft("https://example.com/")
        assertEquals(TagItem.Type.LINK, draft.type)
        assertEquals("https://example.com/", draft.content)
        assertEquals("https://example.com/", draft.label)
    }

    @Test
    fun httpUrlUsesItsTrimmedContent() {
        val draft = sharedItemDraft(" \nhttp://example.com/path?q=value\t ")
        assertEquals(TagItem.Type.LINK, draft.type)
        assertEquals("http://example.com/path?q=value", draft.content)
    }

    @Test
    fun aUrlWithSurroundingTextRemainsANote() {
        listOf("Read https://example.com/", "https://example.com/ read this").forEach { text ->
            val draft = sharedItemDraft(text)
            assertEquals(TagItem.Type.NOTE, draft.type)
            assertEquals(text, draft.content)
        }
    }

    @Test
    fun multilineNoteKeepsTextAndUsesTheFirstNonBlankLine() {
        val text = " \n\t\n  Test note from share  \nSecond line\n"
        val draft = sharedItemDraft(text)
        assertEquals(TagItem.Type.NOTE, draft.type)
        assertEquals(text, draft.content)
        assertEquals("Test note from share", draft.label)
    }

    @Test
    fun nonHttpSchemesRemainNotes() {
        listOf("ftp://example.com/file", "mailto:a@example.com", "https://").forEach { text ->
            assertEquals(TagItem.Type.NOTE, sharedItemDraft(text).type)
        }
    }

    @Test
    fun missingBlankAndNonStringTextAreRejectedWithAMessage() {
        listOf(null, "", " \n\t ", 123, StringBuilder("Test note")).forEach { text ->
            val error = assertThrows(IllegalArgumentException::class.java) { sharedItemDraft(text) }
            assertEquals("Share non-empty text or a web link.", error.message)
        }
    }

    @Test
    fun subjectSuppliesTheLabelWithoutChangingContent() {
        val draft = sharedItemDraft("First line\nSecond line", "  Shared subject  ")
        assertEquals("Shared subject", draft.label)
        assertEquals("First line\nSecond line", draft.content)
        assertEquals(TagItem.Type.NOTE, draft.type)
    }

    @Test
    fun blankOrNonStringSubjectFallsBackToTheFirstNonBlankLine() {
        listOf(null, " \n\t ", 123, StringBuilder("Subject")).forEach { subject ->
            assertEquals("First line", sharedItemDraft("\n  First line \nSecond line", subject).label)
        }
    }

    @Test
    fun aFortyOneCodePointLabelIsCutToForty() {
        assertEquals("é".repeat(MAX_SHARED_LABEL), sharedItemLabel("é".repeat(MAX_SHARED_LABEL + 1)))
    }

    @Test
    fun theFortiethCodePointKeepsAnEmojiWhole() {
        val expected = "a".repeat(MAX_SHARED_LABEL - 1) + "😀"
        val label = sharedItemLabel(expected + "z")
        assertEquals(expected, label)
        assertEquals(MAX_SHARED_LABEL, label.codePointCount(0, label.length))
        assertTrue(Character.isSurrogatePair(label[label.lastIndex - 1], label.last()))
    }

    @Test
    fun aSubjectUsesTheSameCodePointLimit() {
        val expected = "😀".repeat(MAX_SHARED_LABEL)
        assertEquals(expected, sharedItemLabel("Content", " $expected😀 "))
    }

    @Test
    fun editableLabelsKeepTheirExistingValidation() {
        val label = "a".repeat(MAX_SHARED_LABEL + 1)
        assertEquals(label, TagItem.Link(label, "https://example.com/").label)
    }

    @Test
    fun selectingRequiresASharedCreation() {
        assertTrue(selectSharedItemAfterSave(editing = false, requested = true))
        assertFalse(selectSharedItemAfterSave(editing = false, requested = false))
        assertFalse(selectSharedItemAfterSave(editing = true, requested = true))
        assertFalse(selectSharedItemAfterSave(editing = true, requested = false))
    }

    // WO-21: a real UTF-8 record one byte over the file limit pins the exact whole-file count.
    @Test
    fun oversizedNoteReportsTheExactWholeFileByteCount() {
        val message = TagItem.Note("Dummy note", "é".repeat(506) + "a").ndefMessage()
        assertEquals(1023, message.size)
        assertThrows(NdefTooLargeException::class.java) { NdefMessage.ndefFile(message) }
        val resources = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val plurals = resources.getElementsByTagName("plurals")
        val items = (0 until plurals.length).map { plurals.item(it) as Element }
            .single { it.getAttribute("name") == "error_too_long" }.getElementsByTagName("item")
        val template = (0 until items.length).map { items.item(it) as Element }
            .single { it.getAttribute("quantity") == "other" }.textContent
        assertEquals("This item is 1025 bytes; the tag holds at most 1024. Not saved.",
            tooLargeItemMessage(template, message))
    }

    // WO-21: the manifest permits only plain-text sharing and leaves every existing export unchanged.
    @Test
    fun manifestAddsOnlyThePlainTextShareReceiver() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val namespace = "http://schemas.android.com/apk/res/android"
        val permissions = document.getElementsByTagName("uses-permission")
        assertEquals(1, permissions.length)
        assertEquals("android.permission.NFC", (permissions.item(0) as Element).getAttributeNS(namespace, "name"))
        val application = document.getElementsByTagName("application").item(0)
        val components = (0 until application.childNodes.length)
            .mapNotNull { application.childNodes.item(it) as? Element }
            .filter { it.tagName in setOf("activity", "activity-alias", "service", "receiver", "provider") }
            .associateBy { it.getAttributeNS(namespace, "name") }
        val exports = mapOf(".MainActivity" to "true", ".EditItemActivity" to "false",
            ".BoothActivity" to "false", ".NdefHostApduService" to "true", ".ShareReceiverActivity" to "true")
        assertEquals(exports, components.mapValues { it.value.getAttributeNS(namespace, "exported") })
        val filters = components.mapValues { it.value.getElementsByTagName("intent-filter") }
        assertEquals(mapOf(".MainActivity" to 1, ".EditItemActivity" to 0, ".BoothActivity" to 0,
            ".NdefHostApduService" to 1, ".ShareReceiverActivity" to 1), filters.mapValues { it.value.length })
        val filter = filters.getValue(".ShareReceiverActivity").item(0) as Element
        val entries = (0 until filter.childNodes.length).mapNotNull { filter.childNodes.item(it) as? Element }
        assertEquals(listOf("action", "category", "data"), entries.map { it.tagName })
        assertEquals("android.intent.action.SEND", entries[0].getAttributeNS(namespace, "name"))
        assertEquals("android.intent.category.DEFAULT", entries[1].getAttributeNS(namespace, "name"))
        assertEquals(1, entries[2].attributes.length)
        assertEquals("text/plain", entries[2].getAttributeNS(namespace, "mimeType"))
        assertEquals("android.intent.action.MAIN", (filters.getValue(".MainActivity").item(0) as Element)
            .getElementsByTagName("action").item(0).let { (it as Element).getAttributeNS(namespace, "name") })
        assertEquals("android.nfc.cardemulation.action.HOST_APDU_SERVICE",
            (filters.getValue(".NdefHostApduService").item(0) as Element).getElementsByTagName("action")
                .item(0).let { (it as Element).getAttributeNS(namespace, "name") })
    }

    // WO-21: styled URL and note shares retain the same classification and content as plain text.
    @Test
    fun charSequenceTextProducesTheEquivalentStringDraft() {
        listOf(" https://example.com/ ", "First line\nSecond line").forEach { text ->
            assertEquals(sharedItemDraft(text), sharedItemDraftFromExtras(StringBuilder(text)))
        }
    }

    // WO-21: a styled subject changes only the default label, just like a String subject.
    @Test
    fun charSequenceSubjectProducesTheEquivalentStringDraft() {
        val text = "First line\nSecond line"
        val subject = "  Shared subject 😀  "
        assertEquals(sharedItemDraft(text, subject),
            sharedItemDraftFromExtras(StringBuilder(text), StringBuilder(subject)))
    }

    // WO-21: unsupported extras cannot be stringified, and a missing or blank text extra still fails.
    @Test
    fun nonCharSequenceAndEmptyExtrasRemainRejected() {
        val unsupported: Any = object {
            override fun toString(): String = error("Unsupported extras must not be stringified")
        }
        val error = assertThrows(IllegalArgumentException::class.java) { sharedItemDraft(unsupported) }
        assertEquals("Share non-empty text or a web link.", error.message)
        listOf(unsupported as? CharSequence, StringBuilder(), StringBuilder(" \n\t ")).forEach { text ->
            val missing = assertThrows(IllegalArgumentException::class.java) { sharedItemDraftFromExtras(text) }
            assertEquals("Share non-empty text or a web link.", missing.message)
        }
    }

    // WO-21: casing and parameters may vary, but accepting HTML would widen the sharing contract.
    @Test
    fun mimeNormalizationAcceptsOnlyPlainText() {
        listOf("text/plain", "Text/Plain; charset=UTF-8", " TEXT/PLAIN ").forEach { type ->
            assertTrue(isSharedTextMimeType(type))
        }
        listOf(null, "", "text/html", "text/html; charset=UTF-8", "image/png").forEach { type ->
            assertFalse(isSharedTextMimeType(type))
        }
    }

    // WO-21: the forwarding activity has no visible window while the private editor opens.
    @Test
    fun shareReceiverUsesTheNoDisplayTheme() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val namespace = "http://schemas.android.com/apk/res/android"
        val activities = document.getElementsByTagName("activity")
        val receiver = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(namespace, "name") == ".ShareReceiverActivity" }
        assertEquals("@android:style/Theme.NoDisplay", receiver.getAttributeNS(namespace, "theme"))
    }

    // WO-21: the user-visible rejection resource keeps the same meaning as the pure validation error.
    @Test
    fun sharedTextErrorIsAvailableAsAStringResource() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val strings = document.getElementsByTagName("string")
        val error = (0 until strings.length).map { strings.item(it) as Element }
            .single { it.getAttribute("name") == "error_shared_text" }.textContent
        assertEquals("Share non-empty text or a web link.", error)
    }
}
