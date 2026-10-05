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

// WO-2: serialize the entire write/read/fallback transaction and never interpret null as empty.
internal class ChipTransaction(
    private val io: ChipIo, // WO-2: real reflection and JVM fakes share the same content-only contract.
    private val pause: (Long) -> Unit, // WO-2: tests advance the retry deterministically without sleeping.
) {
    // WO-2: startup compares first; failed publication immediately attempts a verified EMPTY fallback.
    fun run(goal: ByteArray, reconcile: Boolean): ByteArray? {
        if (reconcile && read()?.contentEquals(goal) == true) return goal.copyOf()
        if (writeVerified(goal, delayedRead = goal.contentEquals(EMPTY))) return goal.copyOf()
        if (!goal.contentEquals(EMPTY) && writeVerified(EMPTY, delayedRead = true)) return EMPTY
        return null
    }

    // WO-2: read-back alone cannot turn a timed-out write into a successful transaction.
    private fun writeVerified(goal: ByteArray, delayedRead: Boolean): Boolean {
        val result = try { io.write(goal.copyOf()) } catch (_: Exception) { null }
        val status = chipWriteStatus(result, goal.size)
        val first = read()
        if (status == ChipWriteStatus.WRITTEN && first?.contentEquals(goal) == true) return true
        if (delayedRead && (first == null || status == ChipWriteStatus.UNKNOWN ||
                status == ChipWriteStatus.STATUS_FAILED)) {
            // WO-2: three bounded native stages inform a retry interval, not a completion guarantee.
            try { pause(READBACK_SETTLE_DELAY_MS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            val later = read()
            return status == ChipWriteStatus.WRITTEN && later?.contentEquals(goal) == true
        }
        return false
    }

    private fun read(): ByteArray? = try { io.read()?.copyOf() } catch (_: Exception) { null }

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
            val verified = transaction.run(request.goal, request.reconcile)
            synchronized(lock) {
                if (request.generation == generation) publish(verified)
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

// WO-2: all Android entry points share one capability check and one serialized background worker.
internal object ChipSync {
    private val lock = Any() // WO-2: initialize the content-only adapter once per app process.
    private var checked = false // WO-2: unavailable phones stay on the unchanged HCE path.
    private var worker: ChipSyncQueue? = null // WO-2: only a detected vendor adapter can accept chip work.
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PocketTag-chip").apply { isDaemon = true }
    } // WO-2: complete transactions, including fallbacks and delayed reads, cannot overlap.

    fun available(context: Context): Boolean = worker(context) != null

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
    private fun worker(context: Context): ChipSyncQueue? = synchronized(lock) {
        if (!checked) {
            checked = true
            val app = context.applicationContext
            NxpT4tNfcee.detect(app)?.let { io ->
                worker = ChipSyncQueue(ChipTransaction(io, Thread::sleep), executor::execute,
                    invalidate = { TagPrefs.setChipPending(app) }, // WO-2: new work clears proof without claiming failure.
                    publish = { verified -> TagPrefs.setLastVerifiedChip(app, verified) })
            }
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
    fun message(context: Context, display: ChipDisplay, label: String): String = when (display) {
        ChipDisplay.PENDING -> context.getString(R.string.chip_pending)
        ChipDisplay.UNKNOWN -> context.getString(R.string.chip_unknown)
        ChipDisplay.SERVING -> context.getString(R.string.chip_serving, label)
        ChipDisplay.EMPTY -> context.getString(R.string.chip_empty)
        ChipDisplay.OFF_EMPTY -> context.getString(R.string.chip_off_empty)
        ChipDisplay.ITEM_FAILED -> context.getString(R.string.chip_item_failed)
    }
}
