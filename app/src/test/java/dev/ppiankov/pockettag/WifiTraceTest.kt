package dev.ppiankov.pockettag

import android.content.SharedPreferences
import dev.ppiankov.pockettag.Type4Constants.hex
import java.lang.reflect.Proxy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// WO-7: simulate real Type 4 reads through the sanitizer and actual preference-write boundary.
class WifiTraceTest {
    @Test
    fun passwordNeverReachesIncrementalFinalOrLoggedTrace() {
        val fixture = Fixture(wifiState())
        fixture.process("00A4040007D2760000850101")
        fixture.process("00A4000C02E103")
        fixture.process("00B000000F")
        fixture.process("00A4000C02E104")
        val file = fixture.state.ndefFile(true)!!
        for (offset in file.indices step 9) {
            fixture.process(byteArrayOf(0, 0xB0.toByte(), (offset ushr 8).toByte(), offset.toByte(), 9))
        }
        fixture.trace.deactivate("12:00:00", 0)
        assertEquals(setOf("last_trace"), fixture.preferences.values.keys)
        assertTrue(fixture.saved.last().startsWith("Last tap 12:00:00"))
        assertNoSecret(fixture)
        val reads = fixture.logged.filter { it.startsWith("> 00B0") }
        assertTrue(reads.isNotEmpty())
        assertTrue(reads.all { Regex(".*  < <[0-9]+ bytes>").matches(it) })
    }

    @Test
    fun changingSelectionCannotUnmaskTheWifiFileAlreadySelected() {
        val fixture = Fixture(wifiState())
        val original = fixture.state.ndefFile(true)!!
        fixture.process("00A4040007D2760000850101")
        fixture.state = linkState()
        fixture.process("00A4000C02E104")
        val response = fixture.process("00B0000000")
        assertArrayEquals(original + Type4Constants.SW_OK, response)
        assertTrue(fixture.logged.last().endsWith("<${response.size} bytes>"))
        assertNoSecret(fixture)
    }

    @Test
    fun anotherApplicationSelectRefreshesTheSnapshotSensitivity() {
        val fixture = Fixture(wifiState())
        fixture.process("00A4040007D2760000850101")
        fixture.state = linkState()
        fixture.process("00A4040007D2760000850101")
        fixture.process("00A4000C02E104")
        fixture.process("00B0000000")
        assertFalse(fixture.logged.last().contains("bytes>"))
        assertNoSecret(fixture)
    }

    @Test
    fun deactivationAndRefusedSelectionDoNotKeepThePriorSessionFlag() {
        val fixture = Fixture(wifiState())
        fixture.process("00A4040007D2760000850101")
        fixture.tag.reset()
        fixture.trace.deactivate("12:00:00", 0)
        fixture.process("00B0000000")
        assertTrue(fixture.logged.last().endsWith("6A82"))
        fixture.enabled = false
        fixture.process("00A4040007D2760000850101")
        fixture.process("00B0000000")
        assertTrue(fixture.logged.last().endsWith("6A82"))
        assertNoSecret(fixture)
    }

    @Test
    fun fullHistoryCannotBypassLogRedaction() {
        val fixture = Fixture(wifiState(), maxLines = 1)
        fixture.process("00A4040007D2760000850101")
        fixture.process("00A4000C02E104")
        fixture.process("00B0000000")
        assertTrue(fixture.logged.last().contains("bytes>"))
        assertEquals(2, fixture.saved.last().lines().size)
        assertNoSecret(fixture)
    }

    @Test
    fun nonWifiReadStillRecordsTheExistingHexTrace() {
        val fixture = Fixture(linkState())
        fixture.process("00A4040007D2760000850101")
        fixture.process("00A4000C02E104")
        fixture.process("00B0000000")
        assertEquals("> 00B0000000  < 0011D1010D55046578616D706C65…(21B)", fixture.logged.last())
        assertFalse(fixture.logged.last().contains("bytes>"))
    }

    private fun wifiState(): ItemState {
        val item = TagItem.Wifi("Dummy network", "Test", TagItem.Wifi.Security.WPA2_PERSONAL,
            TEST_PASSWORD, id = "wifi-id")
        return ItemState(listOf(item), item.id)
    }

    private fun linkState(): ItemState {
        val item = TagItem.Link("Example", "https://example.com/", "link-id")
        return ItemState(listOf(item), item.id)
    }

    private fun assertNoSecret(fixture: Fixture) {
        val passwordHex = TEST_PASSWORD.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }
        (fixture.saved + fixture.logged).forEach { line ->
            assertFalse(line.contains(TEST_PASSWORD))
            assertFalse(line.contains(passwordHex))
            assertFalse(line.contains(passwordHex.lowercase()))
        }
    }

    // WO-7: the production trace pipeline receives a fake only at its log and preferences boundaries.
    private class Fixture(var state: ItemState, maxLines: Int = 12) {
        var enabled = true
        val preferences = TracePreferences()
        val logged = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val trace = TapTrace(maxLines, 28, log = { logged.add(it) }, persist = {
            TagPrefs.saveTrace(preferences.prefs, it)
            saved.add(preferences.values.getValue("last_trace"))
        })
        val tag = Type4Tag { trace.snapshot(state, enabled) }

        fun process(command: String): ByteArray = process(hex(command))

        fun process(command: ByteArray): ByteArray = tag.process(command).also { trace.record(command, it) }
    }

    // WO-7: use SharedPreferences' real putString/apply surface, preserving every captured write for assertions.
    private class TracePreferences {
        val values = linkedMapOf<String, String>()
        val prefs: SharedPreferences

        init {
            val staged = linkedMapOf<String, String>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString" -> {
                        staged[args!![0] as String] = args[1] as String
                        editor
                    }
                    "apply" -> { values.putAll(staged); staged.clear(); null }
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences.Editor
            prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, _ ->
                when (method.name) {
                    "edit" -> editor
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences
        }
    }

    private companion object {
        const val TEST_PASSWORD = "abcdefgh" // WO-7: synthetic fixture only, never a real network credential.
    }
}
