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

    // WO-19: goal publication and EMPTY publication have distinct content-free verification records.
    @Test fun writtenOutcomesRetainLengthAndVerificationWithoutFallback() {
        for (target in listOf(goal, EMPTY)) {
            val (verified, outcome) = runWithOutcome(FakeChip(), target)
            assertArrayEquals(target, verified)
            assertEquals(ChipOutcome(ChipWriteStatus.WRITTEN, null, target.size, true,
                target.contentEquals(EMPTY), true), outcome)
            assertNull(outcome!!.failureStatus)
        }
    }

    // WO-19: every vendor error and count failure must survive a successful EMPTY fallback.
    @Test fun allGoalFailureNamesAreRecordedSeparatelyFromVerifiedFallback() {
        val failures = listOf<Int?>(null, 0, -99, goal.size - 1, goal.size + 1) + (-11..-1).toList()
        for (failure in failures) {
            val io = FakeChip()
            io.result = { if (it.contentEquals(goal)) failure else it.size }
            val (verified, outcome) = runWithOutcome(io, goal)
            assertArrayEquals(EMPTY, verified)
            assertEquals(ChipOutcome(chipWriteStatus(failure, goal.size), ChipWriteStatus.WRITTEN,
                goal.size, true, true, true), outcome)
            assertEquals(chipWriteStatus(failure, goal.size), outcome!!.failureStatus)
        }
    }

    // WO-19: a matching EMPTY read does not turn a failed vendor result into verified shutdown.
    @Test fun matchingReadWithFailedWriteRecordsMatchWithoutVerification() {
        val failures = listOf<Int?>(null, 0, -99, 3, 5) + (-11..-1).toList()
        for (failure in failures) {
            val io = FakeChip(EMPTY)
            io.result = { failure }
            val (verified, outcome) = runWithOutcome(io, EMPTY)
            assertNull(verified)
            assertEquals(ChipOutcome(chipWriteStatus(failure, EMPTY.size), null,
                EMPTY.size, true, false, true), outcome)
            assertFalse(outcome!!.verified)
        }
    }

    // WO-19: an exact goal count with bad read-back still records the verified final EMPTY target.
    @Test fun readbackMismatchKeepsWrittenStatusAndFinalTargetMatchSeparate() {
        val io = FakeChip()
        io.readResult = { if (io.reads == 1) byteArrayOf(1) else io.content }
        val (verified, outcome) = runWithOutcome(io, goal)
        assertArrayEquals(EMPTY, verified)
        assertEquals(ChipOutcome(ChipWriteStatus.WRITTEN, ChipWriteStatus.WRITTEN,
            goal.size, true, true, true), outcome)
    }

    // WO-19: a failed fallback supersedes the item's failure and reports its own final read.
    @Test fun failedFallbackRecordsItsStatusAndUnmatchedFinalRead() {
        val io = FakeChip(goal)
        io.result = { if (it.contentEquals(goal)) -6 else -2 }
        val (verified, outcome) = runWithOutcome(io, goal)
        assertNull(verified)
        assertEquals(ChipOutcome(ChipWriteStatus.ERROR_INVALID_LENGTH, ChipWriteStatus.ERROR_RF_ACTIVATED,
            goal.size, false, false, true), outcome)
        assertEquals(ChipWriteStatus.ERROR_RF_ACTIVATED, outcome!!.failureStatus)
    }

    // WO-19: only a null final read identifies the EMPTY-write hardware halt, not a mismatched read.
    @Test fun finalNullReadRemainsDistinctFromNonNullMismatch() {
        for (read in listOf<ByteArray?>(null, byteArrayOf(1))) {
            val io = FakeChip()
            io.readResult = { read }
            val (verified, outcome) = runWithOutcome(io, EMPTY)
            assertNull(verified)
            assertEquals(ChipOutcome(ChipWriteStatus.WRITTEN, null, EMPTY.size, false,
                false, read != null), outcome)
        }
    }

    // WO-19: the original bounded delayed read remains authoritative for the recorded outcome.
    @Test fun delayedReadRecordsTheFinalAvailableMatchingObservation() {
        val io = FakeChip()
        io.readResult = { if (io.reads == 1) null else io.content }
        val delays = mutableListOf<Long>()
        val outcomes = mutableListOf<ChipOutcome>()
        val verified = ChipTransaction(io, delays::add).run(EMPTY, false, outcomes::add)
        assertArrayEquals(EMPTY, verified)
        assertEquals(listOf(6300L), delays)
        assertEquals(2, io.reads)
        assertEquals(ChipOutcome(ChipWriteStatus.WRITTEN, null, EMPTY.size, true, true, true), outcomes.single())
    }

    // WO-19: interruption records a completed unknown result without changing the verification rule.
    @Test fun interruptedDelayedReadReportsFailureAndRestoresTestThreadState() {
        val io = FakeChip()
        io.readResult = { null }
        val outcomes = mutableListOf<ChipOutcome>()
        try {
            assertNull(ChipTransaction(io) { throw InterruptedException() }.run(EMPTY, false, outcomes::add))
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(ChipOutcome(ChipWriteStatus.WRITTEN, null, EMPTY.size, false, false, false),
                outcomes.single())
        } finally {
            Thread.interrupted()
        }
    }

    // WO-19: a no-write reconciliation must preserve the last actual write's diagnostics.
    @Test fun matchingReconcileDoesNotReplaceLastWriteHistory() {
        val io = FakeChip()
        io.result = { if (it.contentEquals(goal)) -2 else it.size }
        val prefs = OutcomePrefs()
        transaction(io).run(goal, false) { TagPrefs.setChipOutcome(prefs.preferences, it) }
        val before = prefs.values.toMap()
        val writeCount = io.writes.size
        assertArrayEquals(EMPTY, transaction(io).run(EMPTY, true) {
            TagPrefs.setChipOutcome(prefs.preferences, it)
        })
        assertEquals(writeCount, io.writes.size)
        assertEquals(before, prefs.values)
    }

    // WO-19: the outcome type structurally excludes byte arrays and arbitrary content containers.
    @Test fun outcomeFieldsContainOnlyStatusAndNumberOrBooleanTypes() {
        val fields = ChipOutcome::class.java.declaredFields.filterNot {
            java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        val allowed = setOf(ChipWriteStatus::class.java, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType)
        assertEquals(6, fields.size)
        assertTrue(fields.all { it.type in allowed })
    }

    // WO-19: persistence contains names and integers only and cannot inherit an earlier fallback.
    @Test fun outcomePreferencesRoundTripWithoutContentAndClearPreviousFallback() {
        val prefs = OutcomePrefs()
        val failed = FakeChip().apply { result = { if (it.contentEquals(goal)) -6 else it.size } }
        val fallback = runWithOutcome(failed, goal).second!!
        TagPrefs.setChipOutcome(prefs.preferences, fallback)
        assertEquals(fallback, TagPrefs.lastChipOutcome(prefs.preferences))
        assertTrue(prefs.values.values.all { it is String || it is Int })
        assertTrue(prefs.values.values.filterIsInstance<String>().all { value ->
            ChipWriteStatus.entries.any { it.name == value }
        })
        assertEquals(goal.size, prefs.values["chip_goal_length"])
        assertEquals(1, prefs.values["chip_readback_matched"])
        assertEquals(1, prefs.values["chip_verified_empty"])
        val written = runWithOutcome(FakeChip(), goal).second!!
        TagPrefs.setChipOutcome(prefs.preferences, written)
        assertFalse(prefs.values.containsKey("chip_fallback_status"))
        assertEquals(written, TagPrefs.lastChipOutcome(prefs.preferences))
        assertEquals(0, prefs.values["chip_verified_empty"])
    }

    // WO-19: malformed diagnostic fields are unavailable rather than guessed or written over.
    @Test fun malformedOutcomePreferencesDoNotYieldADiagnosticRecord() {
        assertNull(TagPrefs.lastChipOutcome(OutcomePrefs().preferences))
        val valid = OutcomePrefs()
        TagPrefs.setChipOutcome(valid.preferences, runWithOutcome(FakeChip(), goal).second!!)
        val badValues = listOf(
            "chip_write_status" to "not-a-status", "chip_fallback_status" to 1,
            "chip_goal_length" to -1, "chip_goal_length" to "25",
            "chip_readback_matched" to true, "chip_verified_empty" to 2,
            "chip_readback_available" to "1",
        )
        for ((key, value) in badValues) {
            val prefs = OutcomePrefs(valid.values + (key to value))
            val before = prefs.values.toMap()
            assertNull(TagPrefs.lastChipOutcome(prefs.preferences))
            assertEquals(before, prefs.values)
        }
    }

    // WO-19: successful EMPTY fallback retains an RF item failure, while later failures supersede it.
    @Test fun rfHintUsesTheLastUnverifiedAttempt() {
        val cases = listOf(Triple(-2, EMPTY.size, true), Triple(-2, -6, false),
            Triple(-6, -2, true), Triple(-6, EMPTY.size, false))
        for ((goalResult, emptyResult, expectedHint) in cases) {
            val io = FakeChip()
            io.result = { if (it.contentEquals(goal)) goalResult else emptyResult }
            val (verified, outcome) = runWithOutcome(io, goal)
            val display = chipDisplay(goal, true, verified?.chipHex())
            assertEquals(expectedHint, chipRfHint(display, outcome))
        }
        val written = runWithOutcome(FakeChip(), goal).second!!
        assertFalse(chipRfHint(ChipDisplay.SERVING, written))
    }

    // WO-19: only an RF failure in UNKNOWN or ITEM_FAILED may show the separation hint.
    @Test fun nonRfErrorsAndPendingOrVerifiedStatesNeverShowRfHint() {
        for (result in (-11..-1).toList() + listOf(0, 3, 5, -99)) {
            val io = FakeChip().apply { this.result = { result } }
            val outcome = runWithOutcome(io, EMPTY).second!!
            for (display in ChipDisplay.entries) {
                assertEquals(result == -2 && display in listOf(ChipDisplay.UNKNOWN, ChipDisplay.ITEM_FAILED),
                    chipRfHint(display, outcome))
            }
        }
        assertFalse(chipRfHint(ChipDisplay.UNKNOWN, null))
        assertFalse(chipRfHint(null, runWithOutcome(FakeChip(), EMPTY).second))
    }

    // WO-19: RF-failed items have the named Retry action without adding it to pending or verified states.
    @Test fun retryIsAvailableForRfItemFailuresAndExistingUnknownWarnings() {
        val io = FakeChip().apply { result = { if (it.contentEquals(goal)) -2 else it.size } }
        val rf = runWithOutcome(io, goal).second!!
        assertTrue(chipRetryAvailable(ChipDisplay.ITEM_FAILED, rf))
        assertTrue(chipRetryAvailable(ChipDisplay.UNKNOWN, null))
        val other = FakeChip().apply { result = { if (it.contentEquals(goal)) -6 else it.size } }
        assertFalse(chipRetryAvailable(ChipDisplay.ITEM_FAILED, runWithOutcome(other, goal).second))
        assertFalse(chipRetryAvailable(ChipDisplay.PENDING, rf))
        assertFalse(chipRetryAvailable(ChipDisplay.OFF_EMPTY, rf))
    }

    // WO-19: stale goal and fallback results cannot publish metadata ahead of a newer accepted goal.
    @Test fun outcomeAndProofShareTheGenerationGuardDuringFallback() {
        val io = FakeChip()
        val tasks = mutableListOf<Runnable>()
        val results = mutableListOf<ByteArray?>()
        val outcomes = mutableListOf<ChipOutcome>()
        val queue = ChipSyncQueue(transaction(io), tasks::add, {}, { verified ->
            assertEquals(1, outcomes.size)
            results.add(verified)
        }, outcomes::add)
        val second = TagItem.Link("Second", "https://example.com/second", "second").ndefMessage()
        io.result = { if (it.contentEquals(goal)) -6 else it.size }
        io.onWrite = { if (io.writes.size == 2) queue.request(second) }
        queue.request(goal)
        tasks.single().run()
        assertArrayEquals(second, results.single())
        assertEquals(ChipOutcome(ChipWriteStatus.WRITTEN, null, second.size, true, false, true), outcomes.single())
        assertEquals(3, io.writes.size)
    }

    // WO-19: the supplied executor owns the single detector invocation, never request or availability reads.
    @Test fun detectionRunsOnceOnlyInsideQueuedExecutorWork() {
        val io = FakeChip()
        val tasks = mutableListOf<Runnable>()
        var executing = false
        var detections = 0
        val startup = ChipStartup(tasks::add, {
            assertTrue(executing)
            detections++
            ChipSyncQueue(transaction(io), tasks::add, {}, {})
        }, {})
        assertFalse(startup.available())
        startup.request(goal, true)
        startup.request(goal, true)
        repeat(3) { assertFalse(startup.available()) }
        assertEquals(0, detections)
        assertEquals(1, tasks.size)
        executing = true
        tasks.removeAt(0).run()
        executing = false
        assertTrue(startup.available())
        startup.request(EMPTY, false)
        repeat(3) { assertTrue(startup.available()) }
        assertEquals(1, detections)
        assertEquals(1, tasks.size)
    }

    // WO-19: early edits retain startup reconciliation, including global off with chip mode off.
    @Test fun preDetectionReconciliationSurvivesLaterRequestsAndEmptiesStaleContent() {
        val io = FakeChip(goal)
        val tasks = mutableListOf<Runnable>()
        val results = mutableListOf<ByteArray?>()
        val startup = ChipStartup(tasks::add, {
            ChipSyncQueue(transaction(io), tasks::add, {}, results::add)
        }, {})
        val offGoal = chipGoal(selected, serving = false, chipMode = false)
        startup.request(offGoal, true)
        startup.request(offGoal, false)
        tasks.removeAt(0).run()
        tasks.removeAt(0).run()
        assertEquals(2, io.reads)
        assertArrayEquals(EMPTY, io.writes.single())
        assertArrayEquals(EMPTY, results.single())
    }

    // WO-19: a retained early reconciliation avoids a redundant write when the latest goal already matches.
    @Test fun preDetectionMatchingReconcileStillReadsWithoutWriting() {
        val io = FakeChip(EMPTY)
        val tasks = mutableListOf<Runnable>()
        val results = mutableListOf<ByteArray?>()
        val startup = ChipStartup(tasks::add, {
            ChipSyncQueue(transaction(io), tasks::add, {}, results::add)
        }, {})
        startup.request(goal, true)
        startup.request(EMPTY, false)
        tasks.removeAt(0).run()
        tasks.removeAt(0).run()
        assertEquals(1, io.reads)
        assertTrue(io.writes.isEmpty())
        assertArrayEquals(EMPTY, results.single())
    }

    // WO-19: the last pre-detection goal is copied before the caller can mutate its bytes.
    @Test fun earlyGoalsCoalesceAndCopyTheirContentBeforeDetection() {
        val io = FakeChip(goal)
        val tasks = mutableListOf<Runnable>()
        val startup = ChipStartup(tasks::add, {
            ChipSyncQueue(transaction(io), tasks::add, {}, {})
        }, {})
        startup.request(goal, true)
        val empty = EMPTY
        startup.request(empty, false)
        empty[0] = 0
        tasks.removeAt(0).run()
        tasks.removeAt(0).run()
        assertArrayEquals(EMPTY, io.writes.single())
    }

    // WO-19: a request made while detection is executing must transfer with the latest goal.
    @Test fun requestArrivingDuringDetectionIsRetained() {
        val io = FakeChip(goal)
        val tasks = mutableListOf<Runnable>()
        lateinit var startup: ChipStartup
        startup = ChipStartup(tasks::add, {
            startup.request(EMPTY, false)
            ChipSyncQueue(transaction(io), tasks::add, {}, {})
        }, {})
        startup.request(goal, true)
        tasks.removeAt(0).run()
        tasks.removeAt(0).run()
        assertArrayEquals(EMPTY, io.writes.single())
    }

    // WO-19: a ready worker invalidates a running generation immediately instead of deferring the request.
    @Test fun readyRequestsInvalidateRunningWorkBeforeItCanPublish() {
        val io = FakeChip()
        val tasks = mutableListOf<Runnable>()
        val results = mutableListOf<ByteArray?>()
        var invalidations = 0
        val startup = ChipStartup(tasks::add, {
            ChipSyncQueue(transaction(io), tasks::add, { invalidations++ }, results::add)
        }, {})
        startup.request(goal, false)
        tasks.removeAt(0).run()
        io.onWrite = {
            if (io.writes.size == 1) {
                startup.request(EMPTY, false)
                assertEquals(2, invalidations)
            }
        }
        tasks.removeAt(0).run()
        assertEquals(1, results.size)
        assertArrayEquals(EMPTY, results.single())
    }

    // WO-19: unavailable capability is cached without executing a transaction or retrying detection.
    @Test fun unavailableDetectionDropsNoSupportedWorkAndNeverStartsChipIo() {
        val tasks = mutableListOf<Runnable>()
        val notifications = mutableListOf<Boolean>()
        var detections = 0
        val startup = ChipStartup(tasks::add, { detections++; null }, notifications::add)
        startup.request(goal, true)
        tasks.removeAt(0).run()
        startup.request(EMPTY, false)
        assertFalse(startup.available())
        assertTrue(tasks.isEmpty())
        assertEquals(1, detections)
        assertEquals(listOf(false, false), notifications)
    }

    // WO-19: completion must announce a changed capability flag after cached readiness and pending proof.
    @Test fun availabilityNotificationSeesCachedResultAndPendingProof() {
        val io = FakeChip()
        val tasks = mutableListOf<Runnable>()
        var persistedAvailable = true
        var verifiedHex: String? = goal.chipHex()
        val changes = mutableListOf<Boolean>()
        lateinit var startup: ChipStartup
        startup = ChipStartup(tasks::add, {
            ChipSyncQueue(transaction(io), tasks::add, { verifiedHex = TagPrefs.CHIP_PENDING }, {})
        }, { available ->
            assertEquals(available, startup.available())
            if (available) assertEquals(TagPrefs.CHIP_PENDING, verifiedHex)
            if (persistedAvailable != available) changes.add(available)
            persistedAvailable = available
        })
        startup.request(goal, true)
        tasks.removeAt(0).run()
        assertEquals(listOf(false, true), changes)
        assertTrue(startup.available())
        assertEquals(TagPrefs.CHIP_PENDING, verifiedHex)
    }

    // WO-19: the Android wiring must keep detection in queued work and refresh both screens after completion.
    @Test fun productionDetectionUsesExecutorAndBothScreensObserveAvailability() {
        val directory = listOf(File("src/main/java/dev/ppiankov/pockettag"),
            File("app/src/main/java/dev/ppiankov/pockettag")).first { it.isDirectory }
        val source = File(directory, "ChipSync.kt").readText()
        val available = source.substringAfter("fun available(context: Context): Boolean").substringBefore("\n\n")
        assertFalse(available.contains("detect("))
        assertFalse(available.contains("worker(context)"))
        assertEquals(1, Regex("NxpT4tNfcee\\.detect\\(").findAll(source).count())
        assertTrue(source.contains("ChipStartup(executor::execute, detect = {"))
        val startup = source.substringAfter("internal class ChipStartup(").substringBefore("internal object ChipSync")
        assertTrue(startup.indexOf("val detected = try { detect() }") > startup.indexOf("execute(Runnable {"))
        for (name in listOf("MainActivity.kt", "BoothActivity.kt")) {
            val activity = File(directory, name).readText()
            assertTrue(activity.contains("key == TagPrefs.KEY_CHIP_AVAILABLE"))
            assertTrue(activity.contains("ChipSync.retryAvailable(this, chipDisplay)"))
        }
        val booth = File(directory, "BoothActivity.kt").readText()
            .substringAfter("override fun onResume()").substringBefore("override fun onPause()")
        assertTrue(booth.indexOf("TagPrefs.listen(this, prefsListener)") <
            booth.indexOf("refresh(flashChanges = false)"))
    }

    // WO-19: exercise the production reporting overload while keeping the old fixture and tests untouched.
    private fun runWithOutcome(io: ChipIo, target: ByteArray): Pair<ByteArray?, ChipOutcome?> {
        var outcome: ChipOutcome? = null
        val verified = transaction(io).run(target, false) { outcome = it }
        return verified to outcome
    }

    // WO-19: this new preference fake supports integer metadata without altering the existing fixture.
    private class OutcomePrefs(initial: Map<String, Any> = emptyMap()) {
        val values = initial.toMutableMap() // WO-19: inspect only synthetic outcome fields in JVM tests.
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> error("Unexpected outcome preference call: " + method.name)
            }
        } as SharedPreferences // WO-19: test the real persistence boundary without Android context.

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putInt" -> { changes[args!![0] as String] = args[1]; proxy }
                    "apply" -> {
                        changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        null
                    }
                    else -> error("Unexpected outcome editor call: " + method.name)
                }
            } as SharedPreferences.Editor
        }
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
