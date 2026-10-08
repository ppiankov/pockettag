package dev.ppiankov.pockettag

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// WO-2: verify controller goals, proof, recovery, ordering, and real persistence triggers without hardware.
class ChipModeTest {
    private val link = TagItem.Link("Web", "https://example.com/chip", "web")
    private val selected = ItemState(listOf(link), link.id)
    private val goal = link.ndefMessage()

    @Test fun onlyReadableEnabledOptInCanPublishTheSelection() {
        for (serving in listOf(false, true)) for (mode in listOf(false, true)) {
            for (readable in listOf(false, true)) {
                val state = if (readable) selected else ItemState.unreadable()
                assertArrayEquals(if (serving && mode && readable) goal else EMPTY,
                    chipGoal(state, serving, mode))
            }
        }
    }

    @Test fun missingOrUnselectedItemsServeEmpty() {
        assertArrayEquals(EMPTY, chipGoal(ItemState(emptyList(), null), true, true))
        assertArrayEquals(EMPTY, chipGoal(selected.copy(activeItemId = null), true, true))
        assertArrayEquals(EMPTY, chipGoal(selected.copy(activeItemId = "missing"), true, true))
    }

    @Test fun oversizedContentUsesTheExistingFileLimit() {
        val large = TagItem.Link("Large", "https://example.com/" + "x".repeat(1200), "large")
        assertArrayEquals(EMPTY, chipGoal(ItemState(listOf(large), large.id), true, true))
    }

    @Test fun rawGoalNeverIncludesNlenAndEmptyHasTheAcceptedBytes() {
        assertArrayEquals(byteArrayOf(0xD8.toByte(), 0, 0, 0), EMPTY)
        val actual = chipGoal(selected, true, true)
        assertArrayEquals(goal, actual)
        assertArrayEquals(actual, NdefMessage.ndefFile(actual).drop(2).toByteArray())
        assertFalse(NdefMessage.ndefFile(actual).contentEquals(actual))
        val copy = EMPTY
        copy[0] = 0
        assertEquals(0xD8.toByte(), EMPTY[0])
    }

    @Test fun longRecordsWithinTheFileLimitRemainRaw() {
        val item = TagItem.Link("Long", "https://example.com/" + "x".repeat(900), "long")
        assertArrayEquals(item.ndefMessage(), chipGoal(ItemState(listOf(item), item.id), true, true))
    }

    @Test fun vendorStatusesKeepTheirExactNamesAndCountRules() {
        val names = listOf("STATUS_FAILED", "ERROR_RF_ACTIVATED", "ERROR_MPOS_ON", "ERROR_NFC_NOT_ON",
            "ERROR_INVALID_FILE_ID", "ERROR_INVALID_LENGTH", "ERROR_CONNECTION_FAILED", "ERROR_EMPTY_PAYLOAD",
            "ERROR_NDEF_VALIDATION_FAILED", "ERROR_WRITE_PERMISSION", "ERROR_NFC_OFF_TRIGGERED")
        names.forEachIndexed { index, name -> assertEquals(name, chipWriteStatus(-index - 1, 4).name) }
        assertEquals(ChipWriteStatus.WRITTEN, chipWriteStatus(4, 4))
        assertEquals(ChipWriteStatus.COUNT_MISMATCH, chipWriteStatus(3, 4))
        assertEquals(ChipWriteStatus.COUNT_MISMATCH, chipWriteStatus(5, 4))
        for (result in listOf(null, 0, -12, Int.MIN_VALUE)) {
            assertEquals(ChipWriteStatus.UNKNOWN, chipWriteStatus(result, 4))
        }
    }

    @Test fun exactCountAndReadbackVerifyPublication() {
        val io = FakeChip()
        assertArrayEquals(goal, transaction(io).run(goal, false))
        assertEquals(1, io.writes.size)
        assertArrayEquals(goal, io.writes.single())
    }

    @Test fun everyNonemptyFailureImmediatelyAttemptsEmpty() {
        val failures = listOf<Int?>(null, 0, -99, goal.size - 1, goal.size + 1) + (-11..-1).toList()
        for (failure in failures) {
            val io = FakeChip(goal)
            io.result = { if (it.contentEquals(goal)) failure else it.size }
            assertArrayEquals(EMPTY, transaction(io).run(goal, false))
            assertEquals(2, io.writes.size)
            assertArrayEquals(goal, io.writes[0])
            assertArrayEquals(EMPTY, io.writes[1])
        }
    }

    @Test fun exactCountWithMismatchedReadbackStillFallsBack() {
        val io = FakeChip()
        var reads = 0
        io.readResult = { if (reads++ == 0) byteArrayOf(1, 2) else io.content }
        assertArrayEquals(EMPTY, transaction(io).run(goal, false))
        assertEquals(2, io.writes.size)
    }

    @Test fun unverifiedEmptyNeverMeansOff() {
        val failures = listOf<Int?>(null, 0, 3, 5, -99) + (-11..-1).toList()
        for (failure in failures) {
            val io = FakeChip(EMPTY)
            io.result = { failure }
            assertNull(transaction(io).run(EMPTY, false))
            assertEquals(1, io.writes.size)
        }
    }

    @Test fun emptyReadbackMismatchIsUnknown() {
        val io = FakeChip()
        io.readResult = { byteArrayOf(0xFF.toByte()) }
        assertNull(transaction(io).run(EMPTY, false))
    }

    @Test fun nullReadbackStaysUnknownAndReceivesABoundedDelayedRead() {
        val io = FakeChip()
        io.readResult = { null }
        val delays = mutableListOf<Long>()
        assertNull(ChipTransaction(io, delays::add).run(EMPTY, false))
        assertEquals(2, io.reads)
        assertEquals(listOf(6300L), delays)
    }

    @Test fun lateMatchingReadCannotRepairAMissingWriteCount() {
        val io = FakeChip(EMPTY)
        io.result = { null }
        val delays = mutableListOf<Long>()
        assertNull(ChipTransaction(io, delays::add).run(EMPTY, false))
        assertEquals(2, io.reads)
        assertEquals(listOf(6300L), delays)
    }

    @Test fun delayedReadRequiresTheOriginalExactWriteCount() {
        val io = FakeChip()
        io.readResult = { if (io.reads == 1) null else io.content }
        assertArrayEquals(EMPTY, transaction(io).run(EMPTY, false))
        assertEquals(2, io.reads)
    }

    @Test fun nonemptyUnknownEmptiesBeforeAnySettleDelay() {
        val io = FakeChip(goal)
        io.result = { if (it.contentEquals(goal)) null else it.size }
        val writesAtDelay = mutableListOf<Int>()
        assertArrayEquals(EMPTY, ChipTransaction(io) { writesAtDelay.add(io.writes.size) }.run(goal, false))
        assertTrue(writesAtDelay.isEmpty())
        assertArrayEquals(EMPTY, io.content)
    }

    @Test fun failureToEmptyAfterPublicationFailureRemainsUnknown() {
        val io = FakeChip(goal)
        io.result = { if (it.contentEquals(goal)) -6 else 0 }
        assertNull(transaction(io).run(goal, false))
        assertArrayEquals(goal, io.content)
        assertArrayEquals(EMPTY, io.writes.last())
    }

    @Test fun exceptionsCannotBecomeVerification() {
        val io = FakeChip()
        io.result = { throw IllegalStateException("Write unavailable") }
        io.readResult = { throw IllegalStateException("Read unavailable") }
        assertNull(transaction(io).run(goal, false))
        assertEquals(2, io.writes.size)
    }

    @Test fun startupDoesNothingWhenTheGoalAlreadyMatches() {
        for (current in listOf(EMPTY, goal)) {
            val io = FakeChip(current)
            assertArrayEquals(current, transaction(io).run(current, true))
            assertTrue(io.writes.isEmpty())
        }
    }

    @Test fun startupWithBothSwitchesOffEmptiesEarlierContent() {
        val io = FakeChip(goal)
        val offGoal = chipGoal(selected, serving = false, chipMode = false)
        assertArrayEquals(EMPTY, transaction(io).run(offGoal, true))
        assertArrayEquals(EMPTY, io.writes.single())
    }

    @Test fun chipModeOffAlsoEmptiesStaleContentWhenServeIsOn() {
        val io = FakeChip(goal)
        assertArrayEquals(EMPTY, transaction(io).run(chipGoal(selected, true, false), true))
        assertArrayEquals(EMPTY, io.writes.single())
    }

    @Test fun unknownStartupReadRequestsAnEmptyWrite() {
        val io = FakeChip()
        io.readResult = { if (io.reads == 1) null else io.content }
        assertArrayEquals(EMPTY, transaction(io).run(EMPTY, true))
        assertEquals(1, io.writes.size)
    }

    @Test fun queuedGoalsCoalesceToTheLatestAndCopyTheirBytes() {
        val io = FakeChip()
        val tasks = mutableListOf<Runnable>()
        val results = mutableListOf<ByteArray?>()
        val queue = ChipSyncQueue(transaction(io), tasks::add, {}, results::add)
        queue.request(goal)
        val empty = EMPTY
        queue.request(empty)
        empty[0] = 0
        assertEquals(1, tasks.size)
        tasks.removeAt(0).run()
        assertArrayEquals(EMPTY, io.writes.single())
        assertArrayEquals(EMPTY, results.single())
    }

    @Test fun runningSaveAThenSaveBThenOffPublishesOnlyEmpty() {
        val io = FakeChip()
        val tasks = mutableListOf<Runnable>()
        val results = mutableListOf<ByteArray?>()
        var invalidations = 0
        val queue = ChipSyncQueue(transaction(io), tasks::add, { invalidations++ }, results::add)
        val second = TagItem.Link("Second", "https://example.com/second", "second").ndefMessage()
        io.onWrite = {
            if (io.writes.size == 1) {
                queue.request(second)
                queue.request(EMPTY)
            }
        }
        queue.request(goal)
        tasks.removeAt(0).run()
        assertEquals(3, invalidations)
        assertEquals(2, io.writes.size)
        assertArrayEquals(goal, io.writes.first())
        assertArrayEquals(EMPTY, io.writes.last())
        assertEquals(1, results.size)
        assertArrayEquals(EMPTY, results.single())
    }

    // WO-2: pending, failed, and verified outcomes must stay distinct even when Serve tag is off.
    @Test fun pendingFailedAndVerifiedStatesRemainDistinct() {
        val cases = listOf(Triple(goal, true, ChipDisplay.SERVING),
            Triple(EMPTY, true, ChipDisplay.EMPTY), Triple(EMPTY, false, ChipDisplay.OFF_EMPTY))
        for ((currentGoal, serving, verifiedDisplay) in cases) {
            assertEquals(ChipDisplay.PENDING, chipDisplay(currentGoal, serving, TagPrefs.CHIP_PENDING))
            assertEquals(ChipDisplay.UNKNOWN, chipDisplay(currentGoal, serving, null))
            assertEquals(verifiedDisplay, chipDisplay(currentGoal, serving, currentGoal.chipHex()))
        }
    }

    // WO-2: queued and running reconciliation cannot display a failure before its final read-back.
    @Test fun runningReconciliationStaysPendingThroughDelayedRead() {
        val io = FakeChip(goal)
        val tasks = mutableListOf<Runnable>()
        var verifiedHex: String? = goal.chipHex()
        var delays = 0
        val queue = ChipSyncQueue(ChipTransaction(io) {
            assertEquals(ChipDisplay.PENDING, chipDisplay(EMPTY, false, verifiedHex))
            delays++
        }, tasks::add, { verifiedHex = TagPrefs.CHIP_PENDING }, { verified ->
            verifiedHex = verified?.chipHex()
        })
        io.onWrite = {
            assertEquals(ChipDisplay.PENDING, chipDisplay(EMPTY, false, verifiedHex))
        }
        io.readResult = {
            assertEquals(ChipDisplay.PENDING, chipDisplay(EMPTY, false, verifiedHex))
            when (io.reads) {
                1 -> goal
                2 -> null
                else -> io.content
            }
        }
        queue.request(EMPTY, reconcile = true)
        assertEquals(ChipDisplay.PENDING, chipDisplay(EMPTY, false, verifiedHex))
        tasks.single().run()
        assertEquals(1, io.writes.size)
        assertEquals(3, io.reads)
        assertEquals(1, delays)
        assertEquals(ChipDisplay.OFF_EMPTY, chipDisplay(EMPTY, false, verifiedHex))
    }

    // WO-2: failed read-back produces the warning only after the current transaction publishes null.
    @Test fun failedTransactionBecomesUnknownOnlyAfterCompletion() {
        val io = FakeChip(goal)
        val tasks = mutableListOf<Runnable>()
        var verifiedHex: String? = goal.chipHex()
        var published = false
        var delays = 0
        val queue = ChipSyncQueue(ChipTransaction(io) {
            assertEquals(ChipDisplay.PENDING, chipDisplay(EMPTY, false, verifiedHex))
            delays++
        }, tasks::add, { verifiedHex = TagPrefs.CHIP_PENDING }, { verified ->
            assertEquals(ChipDisplay.PENDING, chipDisplay(EMPTY, false, verifiedHex))
            assertNull(verified)
            verifiedHex = verified?.chipHex()
            published = true
        })
        io.onWrite = {
            assertEquals(ChipDisplay.PENDING, chipDisplay(EMPTY, false, verifiedHex))
        }
        io.readResult = {
            assertEquals(ChipDisplay.PENDING, chipDisplay(EMPTY, false, verifiedHex))
            null
        }
        queue.request(EMPTY)
        assertEquals(ChipDisplay.PENDING, chipDisplay(EMPTY, false, verifiedHex))
        assertFalse(published)
        tasks.single().run()
        assertTrue(published)
        assertEquals(1, io.writes.size)
        assertEquals(2, io.reads)
        assertEquals(1, delays)
        assertEquals(ChipDisplay.UNKNOWN, chipDisplay(EMPTY, false, verifiedHex))
    }

    @Test fun statusRequiresTheCurrentGoalOrVerifiedEmpty() {
        assertEquals(ChipDisplay.UNKNOWN, chipDisplay(EMPTY, false, null))
        assertEquals(ChipDisplay.UNKNOWN, chipDisplay(EMPTY, false, goal.chipHex()))
        assertEquals(ChipDisplay.OFF_EMPTY, chipDisplay(EMPTY, false, EMPTY.chipHex()))
        assertEquals(ChipDisplay.EMPTY, chipDisplay(EMPTY, true, EMPTY.chipHex()))
        assertEquals(ChipDisplay.SERVING, chipDisplay(goal, true, goal.chipHex()))
        assertEquals(ChipDisplay.ITEM_FAILED, chipDisplay(goal, true, EMPTY.chipHex()))
        assertEquals(ChipDisplay.UNKNOWN, chipDisplay(goal, true, "invalid"))
    }

    @Test fun itemMutationPathsRequestAfterPersistenceAndCountersNeverRequest() {
        val second = TagItem.Link("Second", "https://example.com/second", "second")
        val prefs = FakePrefs(mapOf(ItemStore.ITEMS_KEY to ItemJson.encode(listOf(link, second)),
            ItemStore.ACTIVE_ID_KEY to link.id, TagPrefs.KEY_CHIP_MODE to true))
        val sync = FakeSync(prefs)
        val store = ItemStore(prefs.preferences, sync::request)
        val edited = TagItem.Link("Edited", "https://example.com/edited", link.id)
        store.save(edited)
        assertArrayEquals(edited.ndefMessage(), sync.goals.last())
        store.select(second.id)
        assertArrayEquals(second.ndefMessage(), sync.goals.last())
        store.delete(second.id)
        assertArrayEquals(edited.ndefMessage(), sync.goals.last())
        assertEquals(3, sync.goals.size)
        store.incrementTapCount(link.id)
        store.resetTapCount(link.id)
        assertEquals(3, sync.goals.size)
        store.delete(link.id)
        assertArrayEquals(EMPTY, sync.goals.last())
        assertEquals(4, sync.goals.size)
    }

    @Test fun migrationRequestsExactlyOnceAfterItsAtomicSave() {
        val prefs = FakePrefs(mapOf("url" to "https://example.com/legacy"))
        var requests = 0
        val store = ItemStore(prefs.preferences, {
            requests++
            assertTrue(prefs.values.containsKey(ItemStore.ITEMS_KEY))
            assertTrue(prefs.values.containsKey(ItemStore.ACTIVE_ID_KEY))
        })
        store.load()
        store.load()
        assertEquals(1, requests)
    }

    @Test fun servingAndChipModePersistBeforeRequestIncludingBothSwitchesOff() {
        val prefs = FakePrefs(mapOf(ItemStore.ITEMS_KEY to ItemJson.encode(listOf(link)),
            ItemStore.ACTIVE_ID_KEY to link.id))
        val sync = FakeSync(prefs)
        TagPrefs.setEnabled(prefs.preferences, false, sync::request)
        assertArrayEquals(EMPTY, sync.goals.last())
        assertEquals(false, prefs.values[TagPrefs.KEY_ENABLED])
        TagPrefs.setChipMode(prefs.preferences, true, sync::request)
        assertArrayEquals(EMPTY, sync.goals.last())
        assertEquals(true, prefs.values[TagPrefs.KEY_CHIP_MODE])
        TagPrefs.setEnabled(prefs.preferences, true, sync::request)
        assertArrayEquals(goal, sync.goals.last())
        TagPrefs.setChipMode(prefs.preferences, false, sync::request)
        assertArrayEquals(EMPTY, sync.goals.last())
        assertEquals(4, sync.goals.size)
    }

    @Test fun productionHasOnlyTheThreeSpecifiedGoalWriteCallSites() {
        val directory = listOf(File("src/main/java/dev/ppiankov/pockettag"),
            File("app/src/main/java/dev/ppiankov/pockettag")).first { it.isDirectory }
        val calls = Regex("ChipSync\\.request\\(")
        val counts = directory.listFiles()!!.filter { it.extension == "kt" }
            .associate { it.name to calls.findAll(it.readText()).count() }.filterValues { it > 0 }
        assertEquals(mapOf("ItemStore.kt" to 1, "NdefHostApduService.kt" to 2), counts)
        val store = File(directory, "ItemStore.kt").readText()
        assertEquals(1, calls.findAll(store.substringAfter("private fun persist(")).count())
    }

    @Test fun processStartupReconcilesBeforeAnyRestoredActivityOrService() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val application = document.getElementsByTagName("application").item(0)
        assertEquals(".PocketTagApplication", application.attributes.getNamedItem("android:name").nodeValue)
        val source = File(manifest.parentFile, "java/dev/ppiankov/pockettag/ChipSync.kt").readText()
        val startup = source.substringAfter("class PocketTagApplication : Application() {").substringBefore("\n}")
        assertTrue(startup.contains("override fun onCreate()"))
        assertTrue(startup.indexOf("ChipSync.reconcile(this)") > startup.indexOf("super.onCreate()"))
    }

    private fun transaction(io: ChipIo) = ChipTransaction(io) { }

    private class FakeChip(initial: ByteArray? = EMPTY) : ChipIo {
        var content = initial?.copyOf()
        val writes = mutableListOf<ByteArray>()
        var reads = 0
        var result: (ByteArray) -> Int? = { it.size }
        var readResult: (() -> ByteArray?)? = null
        var onWrite: (ByteArray) -> Unit = {}
        override fun write(message: ByteArray): Int? {
            writes.add(message.copyOf())
            val count = result(message)
            if (count == message.size) content = message.copyOf()
            onWrite(message)
            return count
        }
        override fun read(): ByteArray? {
            reads++
            return (if (readResult == null) content else readResult!!.invoke())?.copyOf()
        }
    }

    private class FakeSync(private val prefs: FakePrefs) {
        val goals = mutableListOf<ByteArray>()
        fun request() {
            goals.add(chipGoal(ItemStore(prefs.preferences).load(),
                prefs.values[TagPrefs.KEY_ENABLED] as? Boolean ?: true,
                prefs.values[TagPrefs.KEY_CHIP_MODE] as? Boolean ?: false))
        }
    }

    private class FakePrefs(initial: Map<String, Any>) {
        val values = initial.toMutableMap()
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean" -> { changes[args!![0] as String] = args[1]; proxy }
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
