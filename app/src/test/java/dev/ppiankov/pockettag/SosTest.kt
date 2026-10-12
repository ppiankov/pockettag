package dev.ppiankov.pockettag

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// WO-22: exercise serving, privacy policy and atomic persistence without Android services.
class SosTest {
    private val normal = TagItem.Link("Normal", "https://example.com", "normal") // WO-22: dummy normal selection.
    private val emergency = TagItem.IfFound("Help", "Please help quietly.", "Test Contact",
        "+10000000000", id = "help") // WO-22: dummy emergency target, never an operator contact.
    private val items = ItemState(listOf(normal, emergency), normal.id) // WO-22: SOS must not replace this selection.
    private val on = SosState(enabled = true, itemId = emergency.id) // WO-22: persisted override independent of mode.

    @Test fun servingTruthTablePreservesNormalOffAndOverridesWithSos() {
        for (enabled in listOf(false, true)) for (sosEnabled in listOf(false, true)) {
            val sos = on.copy(enabled = sosEnabled)
            assertEquals(if (sosEnabled) emergency else if (enabled) normal else null,
                servedItem(items, enabled, sos))
            assertEquals(normal.id, items.activeItemId)
        }
    }

    @Test fun unreadableMissingOpaqueAndWrongTypeTargetsNeverFallBack() {
        val opaque = ItemState(ItemDocument(listOf(ItemEntry(normal, ""),
            ItemEntry(null, "{\"id\":\"help\",\"type\":\"future\"}"))), normal.id)
        for (state in listOf(ItemState.unreadable(), items.delete(emergency.id), opaque, items)) {
            val sos = if (state === items) on.copy(itemId = normal.id) else on
            assertNull(servedItem(state, true, sos))
            assertNull(servedItem(state, false, sos))
            assertArrayEquals(EMPTY, chipGoal(state, true, true, sos))
        }
        assertNull(servedItem(items, true, on.copy(itemId = null)))
    }

    @Test fun openAndCovertServeIdenticalBytesInHostAndChipPaths() {
        for (mode in SosMode.entries) for (enabled in listOf(false, true)) {
            val sos = on.copy(mode = mode)
            val item = servedItem(items, enabled, sos)
            val trace = TapTrace(12, 28, {}, {})
            assertArrayEquals(NdefMessage.ndefFile(emergency.ndefMessage()), trace.snapshot(item))
            assertArrayEquals(emergency.ndefMessage(), chipGoal(items, enabled, true, sos))
            assertArrayEquals(EMPTY, chipGoal(items, enabled, false, sos))
        }
    }

    @Test fun sosOffRestoresTheUnchangedNormalGoalOrVerifiedEmpty() {
        val off = on.copy(enabled = false)
        assertEquals(normal, servedItem(items, true, off))
        assertArrayEquals(normal.ndefMessage(), chipGoal(items, true, true, off))
        val empty = chipGoal(items, false, true, off)
        assertArrayEquals(EMPTY, empty)
        assertEquals(ChipDisplay.OFF_EMPTY, chipDisplay(empty, false, EMPTY.chipHex()))
        assertEquals(ChipDisplay.UNKNOWN, chipDisplay(empty, false, null))
    }

    @Test fun oversizedSosUsesTheExistingChipAndHostFileLimit() {
        val large = TagItem.IfFound("Large", "x".repeat(1200), "Test Contact", "+10000000000", id = "help")
        val state = ItemState(listOf(normal, large), normal.id)
        assertArrayEquals(EMPTY, chipGoal(state, true, true, on))
        assertNull(TapTrace(12, 28, {}, {}).snapshot(servedItem(state, true, on)))
    }

    @Test fun modeDefaultsToOpenAndPersistsWithoutChangingSelection() {
        val prefs = preferences()
        val sos = SosPrefs(prefs.preferences)
        assertEquals(SosMode.OPEN, sos.load().mode)
        sos.setMode(SosMode.COVERT, runtimePermission = true)
        assertEquals(SosMode.COVERT, SosPrefs(prefs.preferences).load().mode)
        assertEquals(normal.id, prefs.values[ItemStore.ACTIVE_ID_KEY])
        assertEquals(ItemJson.encode(items.document), prefs.values[ItemStore.ITEMS_KEY])
    }

    @Test fun notificationAndTilePolicyChangesImmediatelyWithModeAndPermission() {
        assertTrue(sosPresentation(on, notificationsAllowed = true).notification)
        assertFalse(sosPresentation(on, notificationsAllowed = false).notification)
        assertEquals(R.string.sos_tile_label, sosPresentation(on, true).tileLabel)
        val covert = on.copy(mode = SosMode.COVERT)
        for (allowed in listOf(false, true)) {
            assertFalse(sosPresentation(covert, allowed).notification)
            assertEquals(R.string.app_name, sosPresentation(covert, allowed).tileLabel)
        }
        assertFalse(sosPresentation(on.copy(enabled = false), true).notification)
        assertTrue(sosPresentation(covert.copy(mode = SosMode.OPEN), true).notification)
    }

    @Test fun onlyFirstOpenActivationQueuesPermissionAndCovertNeverDoes() {
        val prefs = preferences()
        val sos = SosPrefs(prefs.preferences)
        sos.setMode(SosMode.COVERT, runtimePermission = true)
        sos.setEnabled(true, runtimePermission = true)
        assertFalse(sosPermissionRequest(sos.load(), runtimePermission = true))
        assertFalse(sos.markPermissionAsked())
        sos.setMode(SosMode.OPEN, runtimePermission = true)
        assertTrue(sosPermissionRequest(sos.load(), runtimePermission = true))
        assertTrue(sos.markPermissionAsked())
        assertFalse(sosPermissionRequest(sos.load(), runtimePermission = true))
        sos.setEnabled(false, runtimePermission = true)
        sos.setEnabled(true, runtimePermission = true)
        assertFalse(sosPermissionRequest(sos.load(), runtimePermission = true))
        assertTrue(sos.load().enabled)
    }

    @Test fun olderAndroidAndProcessRestoreDoNotInventPermissionRequests() {
        val prefs = preferences()
        val sos = SosPrefs(prefs.preferences)
        sos.setEnabled(true, runtimePermission = false)
        assertFalse(sosPermissionRequest(sos.load(), runtimePermission = false))
        assertFalse(sosPermissionRequest(on, runtimePermission = true))
        assertFalse(sosPermissionRequest(on.copy(permissionPending = true, mode = SosMode.COVERT), true))
    }

    @Test fun configuringNewSosPreservesAnAbsentOrExistingNormalSelection() {
        for (active in listOf(null, normal.id)) {
            val state = ItemState(listOf(normal), active)
            val prefs = preferences(state)
            var syncs = 0
            var observed: Pair<ItemState, SosState>? = null
            val store = ItemStore(prefs.preferences, {
                syncs++
                observed = ItemStore(prefs.preferences).load() to SosPrefs(prefs.preferences).load()
            })
            assertTrue(store.configureSos(emergency, SosMode.COVERT, runtimePermission = true))
            assertEquals(active, store.load().activeItemId)
            assertEquals(emergency.id, SosPrefs(prefs.preferences).load().itemId)
            assertEquals(SosMode.COVERT, SosPrefs(prefs.preferences).load().mode)
            assertEquals(1, syncs)
            assertEquals(active, observed!!.first.activeItemId)
            assertEquals(emergency.id, observed!!.second.itemId)
            assertArrayEquals(emergency.ndefMessage(), servedItem(observed!!.first, false,
                observed!!.second.copy(enabled = true))!!.ndefMessage())
        }
    }

    @Test fun togglesNeverWriteTheItemDocumentOrNormalSelection() {
        val prefs = preferences()
        val sos = SosPrefs(prefs.preferences)
        val before = prefs.values.toMap()
        sos.setEnabled(true, true)
        sos.setEnabled(false, true)
        assertEquals(before[ItemStore.ITEMS_KEY], prefs.values[ItemStore.ITEMS_KEY])
        assertEquals(before[ItemStore.ACTIVE_ID_KEY], prefs.values[ItemStore.ACTIVE_ID_KEY])
        assertTrue(prefs.writes.all { it.keys.none { key -> key in setOf(ItemStore.ITEMS_KEY, ItemStore.ACTIVE_ID_KEY) } })
    }

    @Test fun deletingSosTurnsItOffAndRecordsReasonInTheSameEditor() {
        val prefs = preferences()
        prefs.writeSos(on)
        prefs.writes.clear()
        var observed: SosState? = null
        val store = ItemStore(prefs.preferences, { observed = SosPrefs(prefs.preferences).load() })
        store.delete(emergency.id)
        assertEquals(normal.id, store.load().activeItemId)
        assertFalse(observed!!.enabled)
        assertEquals(SosOffReason.ITEM_DELETED, observed!!.offReason)
        assertEquals(1, prefs.writes.size)
        assertTrue(prefs.writes.single().keys.containsAll(setOf(ItemStore.ITEMS_KEY,
            SosPrefs.KEY_ENABLED, SosPrefs.KEY_OFF_REASON)))
    }

    @Test fun deletingAnotherItemOrUnreadableStorageCannotDisableSos() {
        val prefs = preferences()
        prefs.writeSos(on)
        val store = ItemStore(prefs.preferences)
        store.delete(normal.id)
        assertTrue(SosPrefs(prefs.preferences).load().enabled)
        prefs.values[ItemStore.ITEMS_KEY] = "broken"
        val before = prefs.values.toMap()
        store.delete(emergency.id)
        assertFalse(store.configureSos(emergency, SosMode.OPEN, true))
        assertEquals(before, prefs.values)
        assertTrue(SosPrefs(prefs.preferences).load().enabled)
    }

    @Test fun opaqueSosTargetIsNotInferredToHaveBeenDeleted() {
        val state = ItemState(ItemDocument(listOf(ItemEntry(normal, ""),
            ItemEntry(null, "{\"id\":\"help\",\"type\":\"future\"}"))), normal.id)
        val prefs = preferences(state)
        prefs.writeSos(on)
        ItemStore(prefs.preferences).delete(emergency.id)
        assertTrue(SosPrefs(prefs.preferences).load().enabled)
        assertNull(SosPrefs(prefs.preferences).load().offReason)
    }

    @Test fun traceSensitivityAndCountDestinationFollowTheActuallyServedItem() {
        val wifi = TagItem.Wifi("Guest", "Dummy", TagItem.Wifi.Security.OPEN, "", false, "wifi")
        val state = ItemState(listOf(wifi, emergency), wifi.id)
        val lines = mutableListOf<String>()
        val trace = TapTrace(12, 28, lines::add, {})
        val served = servedItem(state, false, on)!!
        trace.snapshot(served)
        trace.record(byteArrayOf(0, 0xB0.toByte()), byteArrayOf(0x12, 0x34))
        assertTrue(lines.single().contains("1234"))
        val prefs = preferences(state)
        val store = ItemStore(prefs.preferences)
        store.incrementTapCount(served.id)
        assertEquals(mapOf(emergency.id to 1), store.tapCounts())
        trace.snapshot(servedItem(state, true, on.copy(enabled = false)))
        trace.record(byteArrayOf(0, 0xB0.toByte()), byteArrayOf(0x12, 0x34))
        assertTrue(lines.last().contains("<2 bytes>"))
    }

    @Test fun boothOverrideUsesEmergencyLabelReadinessAndCountIdentity() {
        val item = servedItem(items, enabled = false, sos = on)!!
        assertEquals(emergency.label, item.label)
        assertEquals(emergency.id, item.id)
        assertEquals(BoothStatus.READY, boothStatus(true, true, true, true, true,
            serving = on.enabled, itemEncodes = NdefMessage.ndefFile(item.ndefMessage()).isNotEmpty()))
        val source = File("src/main/java/dev/ppiankov/pockettag/BoothActivity.kt").readText()
        assertTrue(source.contains("servedItem(savedItems,"))
        assertFalse(source.contains("val selected = savedItems.activeItem"))
        assertTrue(source.contains("key in SosPrefs.KEYS"))
    }

    @Test fun lockSupportKeepsMeasuredSamsungLimitationExplicit() {
        assertEquals(SosLockSupport.P40_SCREEN_ON, sosLockSupport("ELS-N39"))
        assertEquals(SosLockSupport.S25_UNLOCK_REQUIRED, sosLockSupport("SM-S931B"))
        assertEquals(SosLockSupport.UNVERIFIED, sosLockSupport("unknown"))
    }

    @Test fun defaultMessageSubstitutesOnlyTheChosenContactAndRemainsEditable() {
        assertEquals("Please help me quietly. Call Test Contact: +10000000000 or local emergency services. Please do not draw attention.",
            defaultSosMessage("Test Contact", "+10000000000"))
        assertEquals("Please help quietly.", emergency.message)
    }

    @Test fun startupReconciliationUsesTheStoredSosGoalWithServeOff() {
        val prefs = preferences()
        prefs.writeSos(on)
        val tasks = mutableListOf<Runnable>()
        val writes = mutableListOf<ByteArray>()
        val proofs = mutableListOf<ByteArray?>()
        var content = normal.ndefMessage()
        val io = object : ChipIo {
            override fun read(): ByteArray = content.copyOf()
            override fun write(message: ByteArray): Int {
                writes.add(message.copyOf())
                content = message.copyOf()
                return message.size
            }
        }
        val startup = ChipStartup(tasks::add, {
            ChipSyncQueue(ChipTransaction(io) {}, tasks::add, {}, proofs::add)
        }, {})
        startup.request(chipGoal(ItemStore(prefs.preferences).load(), false, true,
            SosPrefs(prefs.preferences).load()), reconcile = true)
        tasks.removeAt(0).run()
        tasks.removeAt(0).run()
        assertArrayEquals(emergency.ndefMessage(), writes.single())
        assertArrayEquals(emergency.ndefMessage(), proofs.single())
        assertEquals(normal.id, prefs.values[ItemStore.ACTIVE_ID_KEY])
    }

    @Test fun missingPermissionOrModeChangeDoesNotAlterEnabledStateOrGoal() {
        val prefs = preferences()
        prefs.writeSos(on)
        val sos = SosPrefs(prefs.preferences)
        for (mode in SosMode.entries) {
            sos.setMode(mode, runtimePermission = true)
            assertTrue(sos.load().enabled)
            assertArrayEquals(emergency.ndefMessage(), chipGoal(ItemStore(prefs.preferences).load(),
                false, true, sos.load()))
            assertFalse(sosPresentation(sos.load(), notificationsAllowed = false).notification)
        }
    }

    @Test fun tileLauncherKeepsTheApi34BoundaryAndCannotPassToggleExtras() {
        val directory = File("src/main/java/dev/ppiankov/pockettag")
        val source = File(directory, "SosTileService.kt").readText()
        val launcher = source.substringAfter("private fun openMain()").substringBefore("private companion object")
        assertTrue(launcher.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE"))
        val branches = launcher.split("} else {")
        assertEquals(2, branches.size)
        assertTrue(branches[0].contains("startActivityAndCollapse(PendingIntent.getActivity"))
        assertTrue(branches[0].contains("PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(branches[1].contains("startActivityAndCollapse(intent)"))
        assertFalse(source.contains("putExtra("))
        val main = File(directory, "MainActivity.kt").readText()
        assertFalse(main.contains("intent.getBooleanExtra"))
        assertFalse(main.contains("requestDismissKeyguard"))
    }

    // WO-22: synthetic preference writes expose the real atomic boundary to assertions.
    private fun preferences(state: ItemState = items) = FakePrefs(mapOf(
        ItemStore.ITEMS_KEY to ItemJson.encode(state.document),
    ) + (state.activeItemId?.let { mapOf(ItemStore.ACTIVE_ID_KEY to it) } ?: emptyMap()))

    // WO-22: initialize a chosen target through the same editor used by atomic deletion.
    private fun FakePrefs.writeSos(state: SosState) {
        SosPrefs(preferences).write(preferences.edit(), state).apply()
    }

    // WO-22: JVM Android interfaces are proxied, with each apply visible as one snapshot change.
    private class FakePrefs(initial: Map<String, Any>) {
        val values = initial.toMutableMap() // WO-22: contains only dummy test data.
        val writes = mutableListOf<Map<String, Any?>>() // WO-22: inspect keys changed by each atomic write.
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences // WO-22: use production persistence without an Android device.

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean" -> { changes[args!![0] as String] = args[1]; proxy }
                    "apply" -> {
                        writes.add(changes.toMap())
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
