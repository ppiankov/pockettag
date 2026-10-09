package dev.ppiankov.pockettag

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// WO-25: timeout policy and isolated persistence protect the existing serving settings.
class MainScreenAwakeTest {
    @Test fun resumedScreenWithOptInStaysAwake() {
        assertTrue(mainScreenAwake(resumed = true, enabled = true))
    }

    @Test fun disablingReleasesTheResumedScreen() {
        val saved = AwakePreferences()
        TagPrefs.setKeepScreenAwake(saved.prefs, true)
        assertTrue(mainScreenAwake(resumed = true, enabled = TagPrefs.keepScreenAwake(saved.prefs)))
        TagPrefs.setKeepScreenAwake(saved.prefs, false)
        assertFalse(mainScreenAwake(resumed = true, enabled = TagPrefs.keepScreenAwake(saved.prefs)))
    }

    @Test fun pausedScreenStaysReleasedAfterPreferenceChanges() {
        val saved = AwakePreferences()
        for (enabled in listOf(true, false, true)) {
            TagPrefs.setKeepScreenAwake(saved.prefs, enabled)
            assertFalse(mainScreenAwake(resumed = false, enabled = TagPrefs.keepScreenAwake(saved.prefs)))
        }
    }

    @Test fun missingPreferenceDefaultsOff() {
        assertFalse(TagPrefs.keepScreenAwake(AwakePreferences().prefs))
    }

    @Test fun malformedPreferencesDefaultOff() {
        for (invalid in listOf("true", 1, 1L, setOf("true"))) {
            val saved = AwakePreferences(linkedMapOf(TagPrefs.KEY_KEEP_SCREEN_AWAKE to invalid))
            assertFalse(TagPrefs.keepScreenAwake(saved.prefs))
        }
    }

    @Test fun savedChoiceSurvivesRecreatedReaders() {
        val values = linkedMapOf<String, Any>()
        TagPrefs.setKeepScreenAwake(AwakePreferences(values).prefs, true)
        assertTrue(TagPrefs.keepScreenAwake(AwakePreferences(values).prefs))
        TagPrefs.setKeepScreenAwake(AwakePreferences(values).prefs, false)
        assertFalse(TagPrefs.keepScreenAwake(AwakePreferences(values).prefs))
    }

    @Test fun savingTouchesOnlyTheAwakePreference() {
        val original = linkedMapOf<String, Any>(
            TagPrefs.KEY_ENABLED to true,
            TagPrefs.KEY_CHIP_MODE to true,
            TagPrefs.KEY_VERIFIED_CHIP to "0000",
            ItemStore.ITEMS_KEY to "[]",
            ItemStore.ACTIVE_ID_KEY to "fixture-item",
            ItemStore.TAP_COUNTS_KEY to "{}",
            "url" to "https://example.com/",
        )
        val saved = AwakePreferences(original.toMutableMap())
        for (enabled in listOf(true, false)) {
            TagPrefs.setKeepScreenAwake(saved.prefs, enabled)
            assertEquals(original + (TagPrefs.KEY_KEEP_SCREEN_AWAKE to enabled), saved.values)
        }
        assertEquals(List(2) { TagPrefs.KEY_KEEP_SCREEN_AWAKE }, saved.writtenKeys)
    }

    // WO-25: exercise the real preference methods without Android runtime dependencies.
    private class AwakePreferences(
        val values: MutableMap<String, Any> = linkedMapOf(), // WO-25: reuse persisted values across reader instances.
    ) {
        val writtenKeys = mutableListOf<String>() // WO-25: catch writes to unrelated preferences even if values are unchanged.
        val prefs: SharedPreferences // WO-25: enforce Android's boolean type check for malformed saved values.

        init {
            val staged = linkedMapOf<String, Any>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putBoolean" -> {
                        val key = args!![0] as String
                        staged[key] = args[1] as Boolean
                        writtenKeys.add(key)
                        editor
                    }
                    "apply" -> { values.putAll(staged); staged.clear(); null }
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences.Editor
            prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getBoolean" -> values[args!![0] as String]?.let { it as Boolean } ?: args[1] as Boolean
                    "edit" -> editor
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences
        }
    }
}
