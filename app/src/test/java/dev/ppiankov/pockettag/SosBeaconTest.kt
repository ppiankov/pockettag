package dev.ppiankov.pockettag

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// WO-28: pin visibility, pulse limits and persistence independently of Android's keyguard.
class SosBeaconTest {
    @Test fun beaconIsAvailableOnlyWhileOpenSosIsOn() {
        for (enabled in listOf(false, true)) for (mode in SosMode.entries) {
            assertEquals(enabled && mode == SosMode.OPEN,
                beaconAvailable(SosState(enabled = enabled, mode = mode)))
        }
    }

    @Test fun defaultStyleIsSteadyAndOwnerLineIsEmpty() {
        val prefs = FakePrefs()
        assertEquals(SosBeaconSettings(), SosBeaconPrefs(prefs.preferences).load())
        assertEquals(SosBeaconStyle.STEADY, SosBeaconPrefs(prefs.preferences).load().style)
        assertEquals("", SosBeaconPrefs(prefs.preferences).load().ownerLine)
        assertTrue(prefs.values.isEmpty())
    }

    @Test fun styleAndOwnerLinePersistWithoutChangingSosOrSelection() {
        val prefs = FakePrefs(mapOf(SosPrefs.KEY_ENABLED to true, SosPrefs.KEY_MODE to SosMode.OPEN.name,
            SosPrefs.KEY_ITEM_ID to "dummy-help", ItemStore.ACTIVE_ID_KEY to "normal"))
        val before = prefs.values.toMap()
        SosBeaconPrefs(prefs.preferences).save(SosBeaconStyle.PULSE, "Test only")
        assertEquals(SosBeaconSettings(SosBeaconStyle.PULSE, "Test only"),
            SosBeaconPrefs(prefs.preferences).load())
        before.forEach { (key, value) -> assertEquals(value, prefs.values[key]) }
    }

    @Test fun unknownStyleFallsBackToSteadyWithoutRepairingPreferences() {
        val prefs = FakePrefs(mapOf(SosBeaconPrefs.KEY_STYLE to "future-style",
            SosBeaconPrefs.KEY_OWNER_LINE to 42))
        val before = prefs.values.toMap()
        assertEquals(SosBeaconSettings(), SosBeaconPrefs(prefs.preferences).load())
        assertEquals(before, prefs.values)
    }

    @Test fun pulseIsNeverFasterThanOneBackgroundChangePerSecond() {
        assertTrue(SOS_PULSE_PERIOD_MS >= 1000L)
        assertTrue(SOS_PULSE_PHASE_MS >= 1000L)
        assertEquals(SOS_PULSE_PERIOD_MS, SOS_PULSE_PHASE_MS * 2)
    }

    @Test fun steadyAlwaysUsesRedAndPulseAlternatesRedWithBlack() {
        for (bright in listOf(false, true)) {
            assertEquals(SOS_BEACON_RED, beaconBackground(SosBeaconStyle.STEADY, bright))
        }
        assertEquals(SOS_BEACON_RED, beaconBackground(SosBeaconStyle.PULSE, true))
        assertEquals(SOS_BEACON_BLACK, beaconBackground(SosBeaconStyle.PULSE, false))
    }

    @Test fun modernPauseClosesWithoutBreakingLegacyLockScreenEntry() {
        assertFalse(beaconFinishesOnPause(24))
        assertFalse(beaconFinishesOnPause(26))
        assertTrue(beaconFinishesOnPause(27))
        assertTrue(beaconFinishesOnPause(34))
        assertTrue(beaconFinishesOnPause(36))
    }

    @Test fun protectedSystemActionsCanReachTheBeaconFromTheNfcUid() {
        val source = File("src/main/java/dev/ppiankov/pockettag/SosBeaconActivity.kt").readText()
        val registration = source.substringAfter("val filter = IntentFilter(").substringBefore("receiverRegistered = true")
        assertTrue(registration.contains("Intent.ACTION_SCREEN_OFF"))
        assertTrue(registration.contains("addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)"))
        assertTrue(registration.contains("Context.RECEIVER_EXPORTED"))
        assertFalse(registration.contains("Context.RECEIVER_NOT_EXPORTED"))
    }

    @Test fun leavingBeaconCannotTurnSosOffOrBypassKeyguard() {
        val source = File("src/main/java/dev/ppiankov/pockettag/SosBeaconActivity.kt").readText()
        assertFalse(source.contains("SosController.setEnabled"))
        assertFalse(source.contains("SosPrefs(this).setEnabled"))
        assertFalse(source.contains("requestDismissKeyguard"))
        assertFalse(source.contains("FLAG_DISMISS_KEYGUARD"))
        assertFalse(source.contains("DISABLE_KEYGUARD"))
        assertFalse(source.contains("WRITE_SETTINGS"))
        assertFalse(source.contains("WakeLock"))
        val pause = source.substringAfter("override fun onPause()").substringBefore("override fun onDestroy()")
        assertTrue(pause.contains("stopPulse()"))
        assertTrue(pause.contains("FLAG_KEEP_SCREEN_ON"))
        assertTrue(pause.contains("unsetPreferredService(this)"))
        assertTrue(pause.contains("screenBrightness = previousBrightness"))
        assertTrue(pause.contains("beaconFinishesOnPause(Build.VERSION.SDK_INT)"))
        assertTrue(pause.contains("finish()"))
        assertFalse(pause.contains("TagPrefs.unlisten"))
        assertFalse(pause.contains("unregisterReceiver"))
        assertTrue(source.substringAfter("Intent.ACTION_SCREEN_OFF ->").substringBefore("\n").contains("finish()"))
        val destroy = source.substringAfter("override fun onDestroy()").substringBefore("private fun")
        assertTrue(destroy.contains("TagPrefs.unlisten"))
        assertTrue(destroy.contains("unregisterReceiver"))
    }

    @Test fun queuedUpdatesCannotResumeAFinishingOrCovertBeacon() {
        val source = File("src/main/java/dev/ppiankov/pockettag/SosBeaconActivity.kt").readText()
        val resume = source.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
        assertTrue(resume.indexOf("isFinishing") < resume.indexOf("FLAG_KEEP_SCREEN_ON"))
        assertTrue(resume.indexOf("beaconAvailable(") < resume.indexOf("FLAG_KEEP_SCREEN_ON"))
        val pulse = source.substringAfter("override fun run()").substringBefore("override fun onReceive(")
        assertTrue(pulse.contains("!resumed || isFinishing"))
        val listener = source.substringAfter("OnSharedPreferenceChangeListener").substringBefore("private val pulse")
        assertTrue(listener.contains("beaconAvailable("))
        assertTrue(listener.contains("root.visibility = View.INVISIBLE"))
        assertTrue(listener.contains("finish()"))
    }

    @Test fun startupChecksOpenModeBeforeShowingTheLockScreenWindow() {
        val source = File("src/main/java/dev/ppiankov/pockettag/SosBeaconActivity.kt").readText()
        val create = source.substringAfter("override fun onCreate(").substringBefore("override fun onResume()")
        assertTrue(create.indexOf("beaconAvailable(") < create.indexOf("setShowWhenLocked(true)"))
        assertTrue(create.contains("Build.VERSION_CODES.O_MR1"))
        assertTrue(create.contains("FLAG_SHOW_WHEN_LOCKED"))
        assertTrue(create.contains("FLAG_TURN_SCREEN_ON"))
        assertTrue(create.contains("setTurnScreenOn(true)"))
    }

    // WO-28: inspect only synthetic settings and keep Android I/O out of the policy tests.
    private class FakePrefs(initial: Map<String, Any> = emptyMap()) {
        val values = initial.toMutableMap() // WO-28: unrelated SOS and selection keys must remain identical.
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences // WO-28: exercise the production settings editor through a JVM proxy.

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { changes[args!![0] as String] = args[1]; proxy }
                    "apply" -> {
                        changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        null
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
