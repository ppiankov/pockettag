package dev.ppiankov.pockettag

import dev.ppiankov.pockettag.Type4Constants.hex
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// WO-7: dummy credentials verify the exact WSC field order without using a real network.
class WifiItemTest {
    @Test
    fun wpa2HasExactMimeRecordAndCredentialAttributes() {
        val item = TagItem.Wifi("Test network", "Test", TagItem.Wifi.Security.WPA2_PERSONAL, "abcdefgh")
        val payload = hex("100E002F10260001011045000454657374100300020020100F00020008" +
            "10270008616263646566676810200006FFFFFFFFFFFF")
        assertEquals("wifi", item.type.storageName)
        assertArrayEquals(hex("D21733") + "application/vnd.wfa.wsc".toByteArray(Charsets.US_ASCII) + payload,
            item.ndefMessage())
    }

    @Test
    fun openNetworkOmitsTheKeyEvenIfTheEditorRetainedText() {
        val item = TagItem.Wifi("Open test", "Test", TagItem.Wifi.Security.OPEN, "unused-test-key")
        val payload = hex("100E002310260001011045000454657374100300020001100F00020001" +
            "10200006FFFFFFFFFFFF")
        assertArrayEquals(hex("D21727") + "application/vnd.wfa.wsc".toByteArray(Charsets.US_ASCII) + payload,
            item.ndefMessage())
    }

    @Test
    fun wpa3UsesThePinnedWpa2PskCredential() {
        val wpa2 = TagItem.Wifi("Test", "Test", TagItem.Wifi.Security.WPA2_PERSONAL, "abcdefgh")
        val wpa3 = TagItem.Wifi("Test", "Test", TagItem.Wifi.Security.WPA3_PERSONAL, "abcdefgh")
        assertArrayEquals(wpa2.ndefMessage(), wpa3.ndefMessage())
    }

    @Test
    fun ssidLengthCountsUtf8Bytes() {
        val item = TagItem.Wifi("Test", "Café", TagItem.Wifi.Security.WPA2_PERSONAL, "abcdefgh")
        val payload = hex("100E0030102600010110450005436166C3A9100300020020100F00020008" +
            "10270008616263646566676810200006FFFFFFFFFFFF")
        assertArrayEquals(hex("D21734") + "application/vnd.wfa.wsc".toByteArray(Charsets.US_ASCII) + payload,
            item.ndefMessage())
        TagItem.Wifi("Test", "é".repeat(16), TagItem.Wifi.Security.OPEN)
        assertThrows(IllegalArgumentException::class.java) {
            TagItem.Wifi("Test", "é".repeat(17), TagItem.Wifi.Security.OPEN)
        }
    }

    @Test
    fun sixtyThreeCharacterPasswordIsAcceptedAndEncoded() {
        val item = TagItem.Wifi("Test", "Test", TagItem.Wifi.Security.WPA2_PERSONAL, "p".repeat(63))
        assertEquals(132, item.ndefMessage().size)
        assertEquals(63, item.password.length)
        val payload = hex("100E006610260001011045000454657374100300020020100F000200081027003F") +
            "p".repeat(63).toByteArray(Charsets.UTF_8) + hex("10200006FFFFFFFFFFFF")
        assertArrayEquals(hex("D2176A") + "application/vnd.wfa.wsc".toByteArray(Charsets.US_ASCII) + payload,
            item.ndefMessage())
    }

    @Test
    fun personalNetworksRejectSevenAndSixtyFourCharacterPasswords() {
        listOf(TagItem.Wifi.Security.WPA2_PERSONAL, TagItem.Wifi.Security.WPA3_PERSONAL).forEach { security ->
            listOf("", "abcdefg", "p".repeat(64)).forEach { password ->
                assertThrows(IllegalArgumentException::class.java) {
                    TagItem.Wifi("Test", "Test", security, password)
                }
            }
            TagItem.Wifi("Test", "Test", security, "abcdefgh")
        }
    }

    // WO-7: a valid character count must not admit Unicode, control characters, or DEL.
    @Test
    fun personalNetworksRejectNonPrintableAsciiPasswords() {
        listOf(TagItem.Wifi.Security.WPA2_PERSONAL, TagItem.Wifi.Security.WPA3_PERSONAL).forEach { security ->
            listOf("abcdefgé", "abcdefg\u001f", "abcdefg\u007f", "abcdefg😀").forEach { password ->
                val error = assertThrows(IllegalArgumentException::class.java) {
                    TagItem.Wifi("Test", "Test", security, password)
                }
                assertEquals("Password must be 8 to 63 ASCII characters.", error.message)
            }
        }
    }

    // WO-7: both ends of printable ASCII are valid, and spaces remain part of the passphrase.
    @Test
    fun personalNetworksAcceptPrintableAsciiBoundaries() {
        val password = " ~".repeat(4)
        listOf(TagItem.Wifi.Security.WPA2_PERSONAL, TagItem.Wifi.Security.WPA3_PERSONAL).forEach { security ->
            val item = TagItem.Wifi("Test", "Test", security, password)
            assertEquals(password, item.password)
        }
    }

    @Test
    fun emptyAndThirtyThreeByteSsidsAreRejected() {
        listOf("", "s".repeat(33)).forEach { ssid ->
            assertThrows(IllegalArgumentException::class.java) { TagItem.Wifi("Test", ssid, TagItem.Wifi.Security.OPEN) }
        }
        assertEquals("  Test  ", TagItem.Wifi("Test", "  Test  ", TagItem.Wifi.Security.OPEN).ssid)
    }

    @Test
    fun jsonRoundTripKeepsCredentialsHiddenChoiceAndIdentity() {
        val item = TagItem.Wifi("Test network", "Test", TagItem.Wifi.Security.WPA3_PERSONAL,
            "abcdefgh", hidden = true, id = "wifi-id")
        val json = ItemJson.encode(listOf(item))
        val restored = ItemJson.decode(json).items.single() as TagItem.Wifi
        assertEquals(item.id, restored.id)
        assertEquals(item.label, restored.label)
        assertEquals(item.ssid, restored.ssid)
        assertEquals(item.security, restored.security)
        assertEquals(item.password, restored.password)
        assertEquals(item.hidden, restored.hidden)
        assertArrayEquals(item.ndefMessage(), restored.ndefMessage())
        assertEquals(json, ItemJson.encode(listOf(restored)))
    }

    @Test
    fun missingOpenPasswordAndHiddenChoiceUseTheDefaults() {
        val document = ItemJson.decode("""[{"id":"wifi-id","type":"wifi","label":"Test","ssid":"Test","security":"OPEN"}]""")
        val item = document.items.single() as TagItem.Wifi
        assertEquals("", item.password)
        assertEquals(false, item.hidden)
    }

    @Test
    fun wrongHiddenTypeRemainsOpaque() {
        val json = """[{"id":"wifi-id","type":"wifi","label":"Test","ssid":"Test","security":"OPEN","hidden":"true"}]"""
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
