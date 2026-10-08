package dev.ppiankov.pockettag

import android.app.Application
import android.content.Context
import java.util.concurrent.Executors

// WO-2: direct activity restoration or a service-started process must also reconcile stale chip content.
class PocketTagApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ChipSync.reconcile(this)
    }
}

// WO-2: NXP validates this empty IL record; callers receive a copy so it cannot be mutated globally.
internal val EMPTY: ByteArray get() = byteArrayOf(0xD8.toByte(), 0, 0, 0)

// WO-2: use the selected raw message only when both switches and the existing file-size gate permit it.
internal fun chipGoal(state: ItemState, serving: Boolean, chipMode: Boolean): ByteArray {
    if (!serving || !chipMode || !state.readable) return EMPTY
    return try {
        state.activeItem?.ndefMessage()?.also { NdefMessage.ndefFile(it) } ?: EMPTY
    } catch (_: Exception) {
        EMPTY
    }
}

// WO-2: a stored verification is meaningful only against the current goal and global serving switch.
internal enum class ChipDisplay { PENDING, UNKNOWN, SERVING, EMPTY, OFF_EMPTY, ITEM_FAILED }

// WO-2: never claim off from an old nonempty verification or from an inconclusive read.
internal fun chipDisplay(goal: ByteArray, serving: Boolean, verifiedHex: String?): ChipDisplay = when {
    // WO-2: queued and running checks have no completed failure to report yet.
    verifiedHex == TagPrefs.CHIP_PENDING -> ChipDisplay.PENDING
    verifiedHex == EMPTY.chipHex() -> when {
        !serving -> ChipDisplay.OFF_EMPTY
        goal.contentEquals(EMPTY) -> ChipDisplay.EMPTY
        else -> ChipDisplay.ITEM_FAILED
    }
    verifiedHex == goal.chipHex() -> ChipDisplay.SERVING
    else -> ChipDisplay.UNKNOWN
}

// WO-2: verification bytes stay in private preferences, never in diagnostics or public artifacts.
internal fun ByteArray.chipHex(): String = joinToString("") { "%02X".format(it) }

// WO-19: diagnostics describe the final attempts without retaining any tag content.
internal data class ChipOutcome(
    val goalStatus: ChipWriteStatus, // WO-19: retain the requested write's vendor status name.
    val fallbackStatus: ChipWriteStatus?, // WO-19: absent unless a failed item attempted EMPTY.
    val goalLength: Int, // WO-19: record the original message length without its bytes.
    val readBackMatched: Boolean, // WO-19: compare the final read against the final attempted target.
    val verifiedEmpty: Boolean, // WO-19: matching bytes alone cannot verify EMPTY after a failed write.
    val finalReadBackAvailable: Boolean, // WO-19: distinguish a null final read for the hardware halt rule.
) {
    // WO-19: a vendor count and a matching final read are both needed for the final target's proof.
    val verified: Boolean get() = (fallbackStatus ?: goalStatus) == ChipWriteStatus.WRITTEN && readBackMatched

    // WO-19: a verified fallback preserves the item failure; a failed fallback supersedes it.
    val failureStatus: ChipWriteStatus? get() = when {
        fallbackStatus != null -> if (verifiedEmpty) goalStatus else fallbackStatus
        !verified -> goalStatus
        else -> null
    }
}

// WO-19: RF guidance belongs only to completed failures that can be retried after separation.
internal fun chipRfHint(display: ChipDisplay?, outcome: ChipOutcome?): Boolean =
    (display == ChipDisplay.UNKNOWN || display == ChipDisplay.ITEM_FAILED) &&
        outcome?.failureStatus == ChipWriteStatus.ERROR_RF_ACTIVATED

// WO-19: an RF-failed item needs the same Retry action named by its recovery hint.
internal fun chipRetryAvailable(display: ChipDisplay?, outcome: ChipOutcome?): Boolean =
    display == ChipDisplay.UNKNOWN || chipRfHint(display, outcome)

// WO-2: serialize the entire write/read/fallback transaction and never interpret null as empty.
internal class ChipTransaction(
    private val io: ChipIo, // WO-2: real reflection and JVM fakes share the same content-only contract.
    private val pause: (Long) -> Unit, // WO-2: tests advance the retry deterministically without sleeping.
) {
    // WO-2: startup compares first; failed publication immediately attempts a verified EMPTY fallback.
    // WO-19: retain the existing proof-only API for callers that do not need diagnostics.
    fun run(goal: ByteArray, reconcile: Boolean): ByteArray? = run(goal, reconcile) { }

    // WO-19: report only actual writes, preserving last-write history when reconciliation already matches.
    fun run(goal: ByteArray, reconcile: Boolean, report: (ChipOutcome) -> Unit): ByteArray? {
        if (reconcile && read()?.contentEquals(goal) == true) return goal.copyOf()
        val goalAttempt = writeVerified(goal, delayedRead = goal.contentEquals(EMPTY))
        val fallback = if (!goalAttempt.verified && !goal.contentEquals(EMPTY)) {
            writeVerified(EMPTY, delayedRead = true)
        } else null
        val finalAttempt = fallback ?: goalAttempt
        val verified = when {
            goalAttempt.verified -> goal.copyOf()
            fallback?.verified == true -> EMPTY
            else -> null
        }
        report(ChipOutcome(goalAttempt.status, fallback?.status, goal.size,
            finalAttempt.readBackMatched, verified?.contentEquals(EMPTY) == true,
            finalAttempt.readBackAvailable))
        return verified
    }

    // WO-2: read-back alone cannot turn a timed-out write into a successful transaction.
    // WO-19: retain status and final-read facts while keeping the verification and delay rules unchanged.
    private fun writeVerified(goal: ByteArray, delayedRead: Boolean): Attempt {
        val result = try { io.write(goal.copyOf()) } catch (_: Exception) { null }
        val status = chipWriteStatus(result, goal.size)
        val first = read()
        if (status == ChipWriteStatus.WRITTEN && first?.contentEquals(goal) == true) {
            return Attempt(status, readBackMatched = true, readBackAvailable = true)
        }
        if (delayedRead && (first == null || status == ChipWriteStatus.UNKNOWN ||
                status == ChipWriteStatus.STATUS_FAILED)) {
            // WO-2: three bounded native stages inform a retry interval, not a completion guarantee.
            try { pause(READBACK_SETTLE_DELAY_MS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                // WO-19: interruption leaves the first read as the last observation, never a new proof.
                return Attempt(status, first?.contentEquals(goal) == true, first != null)
            }
            val later = read()
            // WO-19: diagnostics use the same delayed read that decides verification.
            return Attempt(status, later?.contentEquals(goal) == true, later != null)
        }
        // WO-19: a matching read with a failed vendor status remains unverified.
        return Attempt(status, first?.contentEquals(goal) == true, first != null)
    }

    private fun read(): ByteArray? = try { io.read()?.copyOf() } catch (_: Exception) { null }

    // WO-19: attempt metadata contains only status and comparison facts, never the read bytes.
    private data class Attempt(
        val status: ChipWriteStatus, // WO-19: retain the count/status result for the attempted target.
        val readBackMatched: Boolean, // WO-19: compare bytes locally before discarding them.
        val readBackAvailable: Boolean, // WO-19: a null final read must remain distinguishable.
    ) {
        // WO-19: diagnostic read facts cannot relax the existing exact-count verification rule.
        val verified: Boolean get() = status == ChipWriteStatus.WRITTEN && readBackMatched
    }

    private companion object {
        const val NATIVE_STAGE_TIMEOUT_MS = 2100L // WO-2: NXP's open, data, and close waits each have this timeout.
        const val NATIVE_STAGE_COUNT = 3 // WO-2: a transaction can traverse all three native waits.
        const val READBACK_SETTLE_DELAY_MS = NATIVE_STAGE_TIMEOUT_MS * NATIVE_STAGE_COUNT // WO-2: RF waits can still outlast this delay.
    }
}

// WO-2: one worker coalesces queued goals and publishes only the latest generation's verification.
internal class ChipSyncQueue(
    private val transaction: ChipTransaction, // WO-2: no two app transactions run concurrently.
    private val execute: (Runnable) -> Unit, // WO-2: Android supplies one background executor; JVM tests supply a queue.
    private val invalidate: () -> Unit, // WO-2: a new request cannot inherit an old success indicator.
    private val publish: (ByteArray?) -> Unit, // WO-2: only the current generation updates private verification state.
    private val record: (ChipOutcome) -> Unit = {}, // WO-19: diagnostics obey the same generation guard as proof.
) {
    private val lock = Any() // WO-2: generation checks and result publication form one critical section.
    private var generation = 0L // WO-2: a running operation cannot publish after a newer request arrives.
    private var pending: Request? = null // WO-2: retain only the newest goal while a transaction is running.
    private var running = false // WO-2: schedule at most one draining worker.

    // WO-2: snapshot mutable bytes before accepting a new goal and invalidating the previous proof.
    fun request(goal: ByteArray, reconcile: Boolean = false) {
        synchronized(lock) {
            pending = Request(++generation, goal.copyOf(), reconcile)
            invalidate()
            if (!running) {
                running = true
                execute(Runnable { drain() })
            }
        }
    }

    // WO-2: discard stale results while still finishing any necessary EMPTY fallback before the next goal.
    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                pending.also {
                    pending = null
                    if (it == null) running = false
                }
            } ?: return
            // WO-19: hold metadata locally until the complete transaction passes the generation check.
            var outcome: ChipOutcome? = null
            val verified = transaction.run(request.goal, request.reconcile) { outcome = it }
            synchronized(lock) {
                // WO-19: store metadata before proof so a completed warning cannot inherit an old RF cause.
                if (request.generation == generation) {
                    outcome?.let(record)
                    publish(verified)
                }
            }
        }
    }

    // WO-2: reconciliation and publication carry the same generation and immutable goal snapshot.
    private data class Request(
        val generation: Long, // WO-2: associates an outcome with exactly one accepted goal.
        val goal: ByteArray, // WO-2: copied before asynchronous work begins.
        val reconcile: Boolean, // WO-2: matching startup content needs no additional write.
    )
}

// WO-19: detection and queued startup goals share the transaction executor without blocking availability reads.
internal class ChipStartup(
    private val execute: (Runnable) -> Unit, // WO-19: detection is scheduled before any transaction drain.
    private val detect: () -> ChipSyncQueue?, // WO-19: the injected detector is called only by executor work.
    private val publishAvailable: (Boolean) -> Unit, // WO-19: preference listeners refresh after cached detection.
) {
    private val lock = Any() // WO-19: transfer pending goals and expose readiness in one short critical section.
    private var started = false // WO-19: each process schedules detection once, even when unavailable.
    private var complete = false // WO-19: initial availability is false until detection finishes.
    private var queue: ChipSyncQueue? = null // WO-19: only the detected adapter can execute chip transactions.
    private var pending: Request? = null // WO-19: retain the latest goal while the vendor lookup runs.

    // WO-19: a cached read cannot start detection or wait for the vendor binder call.
    fun available(): Boolean = synchronized(lock) { complete && queue != null }

    // WO-19: requests after detection invalidate their generation immediately on the caller's thread.
    fun request(goal: ByteArray, reconcile: Boolean) = synchronized(lock) {
        if (complete) {
            queue?.request(goal, reconcile)
            return@synchronized
        }
        // WO-19: coalescing early edits must retain the process-start reconciliation of the latest goal.
        pending = Request(goal.copyOf(), reconcile || pending?.reconcile == true)
        if (!started) {
            started = true
            // WO-19: resetting a previous process's flag ensures successful detection emits a change event.
            publishAvailable(false)
            execute(Runnable {
                val detected = try { detect() } catch (_: Exception) { null } catch (_: LinkageError) { null }
                synchronized(lock) {
                    queue = detected
                    complete = true
                    // WO-19: set pending proof before announcing availability to either screen.
                    pending?.let { detected?.request(it.goal, it.reconcile) }
                    pending = null
                    publishAvailable(detected != null)
                }
            })
        }
    }

    // WO-19: the early queue copies content privately and never puts it in diagnostic metadata.
    private data class Request(
        val goal: ByteArray, // WO-19: callers cannot mutate a queued pre-detection goal.
        val reconcile: Boolean, // WO-19: process-start reconciliation survives later early edits.
    )
}

// WO-2: all Android entry points share one capability check and one serialized background worker.
internal object ChipSync {
    private val lock = Any() // WO-2: initialize the content-only adapter once per app process.
    private var checked = false // WO-2: unavailable phones stay on the unchanged HCE path.
    // WO-19: the worker retains early goals while detection runs on the shared executor.
    private var worker: ChipStartup? = null // WO-2: only a detected vendor adapter can accept chip work.
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PocketTag-chip").apply { isDaemon = true }
    } // WO-2: complete transactions, including fallbacks and delayed reads, cannot overlap.

    // WO-19: activity refreshes read the cache without starting reflection or waiting for detection.
    fun available(context: Context): Boolean = synchronized(lock) { worker }?.available() == true

    // WO-2: only the three goal-changing persistence boundaries call this entry point.
    fun request(context: Context) = submit(context, reconcile = false)

    // WO-2: every app start checks for stale content even when both switches are off.
    fun reconcile(context: Context) = submit(context, reconcile = true)

    // WO-2: recovery repeats the current transaction without rewriting any saved item or switch.
    fun retry(context: Context) = submit(context, reconcile = false)

    // WO-2: recompute from freshly persisted items and flags before accepting an asynchronous goal.
    private fun submit(context: Context, reconcile: Boolean) {
        val app = context.applicationContext
        val queue = worker(app) ?: return
        val goal = try {
            chipGoal(ItemStore(app).load(), TagPrefs.enabled(app), TagPrefs.chipMode(app))
        } catch (_: Exception) {
            EMPTY
        }
        queue.request(goal, reconcile)
    }

    // WO-2: optional vendor detection has no probe write and does not change the controller's state.
    // WO-19: constructing startup state does no vendor work; its first goal schedules detection once.
    private fun worker(context: Context): ChipStartup? = synchronized(lock) {
        if (!checked) {
            checked = true
            val app = context.applicationContext
            // WO-19: this supplier is invoked exclusively by ChipStartup's executor task.
            worker = ChipStartup(executor::execute, detect = {
                NxpT4tNfcee.detect(app)?.let { io ->
                    ChipSyncQueue(ChipTransaction(io, Thread::sleep), executor::execute,
                        invalidate = { TagPrefs.setChipPending(app) }, // WO-2: new work clears proof without claiming failure.
                        publish = { verified -> TagPrefs.setLastVerifiedChip(app, verified) },
                        // WO-19: the queue records metadata before publishing the same generation's proof.
                        record = { outcome -> TagPrefs.setChipOutcome(app, outcome) })
                }
            }, publishAvailable = { TagPrefs.setChipAvailable(app, it) })
        }
        worker
    }

    // WO-2: status follows the current goal, never a success from a previous selected item.
    fun display(context: Context, state: ItemState): ChipDisplay = try {
        val serving = TagPrefs.enabled(context)
        chipDisplay(chipGoal(state, serving, TagPrefs.chipMode(context)), serving,
            TagPrefs.lastVerifiedChip(context))
    } catch (_: Exception) {
        ChipDisplay.UNKNOWN
    }

    // WO-2: the selector and booth screen use the same exact verification and recovery wording.
    // WO-19: append RF recovery only for the final failed attempt, retaining every existing status message.
    fun message(context: Context, display: ChipDisplay, label: String): String {
        val message = when (display) {
            ChipDisplay.PENDING -> context.getString(R.string.chip_pending)
            ChipDisplay.UNKNOWN -> context.getString(R.string.chip_unknown)
            ChipDisplay.SERVING -> context.getString(R.string.chip_serving, label)
            ChipDisplay.EMPTY -> context.getString(R.string.chip_empty)
            ChipDisplay.OFF_EMPTY -> context.getString(R.string.chip_off_empty)
            ChipDisplay.ITEM_FAILED -> context.getString(R.string.chip_item_failed)
        }
        // WO-19: pending and verified states never inherit a prior RF failure's hint.
        return if (chipRfHint(display, TagPrefs.lastChipOutcome(context))) {
            message + "\n" + context.getString(R.string.chip_rf_active_hint)
        } else message
    }

    // WO-19: both screens expose the Retry action promised by an RF-failed item's hint.
    fun retryAvailable(context: Context, display: ChipDisplay?): Boolean =
        chipRetryAvailable(display, TagPrefs.lastChipOutcome(context))

    // WO-19: diagnostic text uses only the fixed metadata fields, never labels, URLs, or read bytes.
    fun diagnostics(context: Context): String? {
        if (!available(context)) return null
        val outcome = TagPrefs.lastChipOutcome(context) ?: return null
        val suffix = when {
            outcome.fallbackStatus != null -> if (outcome.verifiedEmpty) {
                context.getString(R.string.chip_diagnostic_empty_verified)
            } else context.getString(R.string.chip_diagnostic_empty_unverified, outcome.fallbackStatus.name)
            outcome.verified -> context.getString(R.string.chip_diagnostic_verified)
            else -> context.getString(R.string.chip_diagnostic_unverified)
        }
        return context.resources.getQuantityString(R.plurals.chip_diagnostic, outcome.goalLength,
            outcome.goalStatus.name, outcome.goalLength, suffix)
    }
}
