package dev.ppiankov.pockettag

import dev.ppiankov.pockettag.Type4Constants.hex
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// WO-10: Android Application Records stay single-record tags with validated ASCII packages.
class AppItemTest {
    @Test
    fun samplePackageHasExactExternalTypeBytes() {
        val item = TagItem.App("Example app", "dev.ppiankov.pockettag")
        assertEquals("app", item.type.storageName)
        assertArrayEquals(hex("D40F16") + "android.com:pkgdev.ppiankov.pockettag".toByteArray(Charsets.US_ASCII),
            item.ndefMessage())
    }

    @Test
    fun longPackageUsesTheExistingFourByteRecordLength() {
        val name = "com." + "a".repeat(252)
        assertArrayEquals(hex("C40F00000100") + ("android.com:pkg" + name).toByteArray(Charsets.US_ASCII),
            TagItem.App("App", name).ndefMessage())
    }

    @Test
    fun invalidPackageNamesAreRejected() {
        listOf("nodots", "1abc.def", "a..b", ".a.b", "a.b.", "a._b", "a.b-c", "a.中文", "")
            .forEach { name ->
                assertThrows(IllegalArgumentException::class.java) { TagItem.App("App", name) }
            }
    }

    @Test
    fun lettersDigitsAndUnderscoresAreAllowedAfterTheFirstLetter() {
        val item = TagItem.App("App", "  Example.app_2.feature3  ")
        assertEquals("Example.app_2.feature3", item.packageName)
    }

    @Test
    fun jsonRoundTripKeepsPackageLabelAndIdentity() {
        val item = TagItem.App("Example app", "com.example.app", "app-id")
        val json = ItemJson.encode(listOf(item))
        val restored = ItemJson.decode(json).items.single() as TagItem.App
        assertEquals(item.id, restored.id)
        assertEquals(item.label, restored.label)
        assertEquals(item.packageName, restored.packageName)
        assertArrayEquals(item.ndefMessage(), restored.ndefMessage())
        assertEquals(json, ItemJson.encode(listOf(restored)))
    }

    @Test
    fun invalidSavedPackageRemainsOpaque() {
        val json = """[{"id":"bad-app","type":"app","label":"App","packageName":"nodots"}]"""
        val document = ItemJson.decode(json)
        assertTrue(document.readable)
        assertTrue(document.items.isEmpty())
        assertEquals(json, ItemJson.encode(document))
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
