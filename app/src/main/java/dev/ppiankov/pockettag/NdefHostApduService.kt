package dev.ppiankov.pockettag

import android.content.Context
import android.content.SharedPreferences
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.text.DateFormat
import java.util.Date

/** The single preferences surface shared by the service and the settings screen. */
object TagPrefs {
    private const val FILE = "pockettag"
    private const val KEY_URL = "url"
    internal const val KEY_ENABLED = "enabled" // WO-15: booth observers share the serving preference key.
    internal const val KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake" // WO-25: normal-screen timeout is an independent opt-in.
    internal const val KEY_CHIP_MODE = "chip_mode" // WO-2: chip publication remains an explicit opt-in.
    internal const val KEY_VERIFIED_CHIP = "last_verified_chip" // WO-2: stores pending work or byte-exact read-back proof.
    internal const val CHIP_PENDING = "pending" // WO-2: in-flight work must not look like a failed transaction.
    private const val KEY_CHIP_CONFIRMED = "chip_mode_confirmed" // WO-2: retain the first-enable acknowledgement.
    private const val KEY_TRACE = "last_trace"
    private const val KEY_SHOW_TRACE = "show_trace"
    internal const val KEY_CHIP_AVAILABLE = "chip_available" // WO-19: refresh screens after this process's detection.
    internal const val KEY_CHIP_WRITE_STATUS = "chip_write_status" // WO-19: retain a vendor status name, never content.
    private const val KEY_CHIP_FALLBACK_STATUS = "chip_fallback_status" // WO-19: describe the optional EMPTY attempt.
    private const val KEY_CHIP_GOAL_LENGTH = "chip_goal_length" // WO-19: diagnose sizes without retaining message bytes.
    private const val KEY_CHIP_READBACK_MATCHED = "chip_readback_matched" // WO-19: compare the final attempted target.
    private const val KEY_CHIP_VERIFIED_EMPTY = "chip_verified_empty" // WO-19: distinguish verified EMPTY from a failed write.
    private const val KEY_CHIP_READBACK_AVAILABLE = "chip_readback_available" // WO-19: preserve the null-read halt evidence.
    private const val CHIP_FLAG_FALSE = 0 // WO-19: diagnostic flags are stored as plain integers.
    private const val CHIP_FLAG_TRUE = 1 // WO-19: the outcome preferences contain only names and integers.
    const val DEFAULT_URL = "https://obstalabs.dev"

    // The trace is always recorded; this only controls whether the screen shows it.
    fun showTrace(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_TRACE, false)

    fun setShowTrace(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_TRACE, show).apply()
    }

    // Last reader exchange, shown on screen so a failed tap can be diagnosed without adb.
    fun lastTrace(context: Context): String? = prefs(context).getString(KEY_TRACE, null)

    fun saveTrace(context: Context, trace: String) {
        saveTrace(prefs(context), trace)
    }

    // WO-7: JVM fakes exercise the actual trace write after response redaction.
    internal fun saveTrace(prefs: SharedPreferences, trace: String) {
        prefs.edit().putString(KEY_TRACE, trace).apply()
    }

    fun url(context: Context): String =
        prefs(context).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    // WO-25: both activity rendering and JVM tests use the same default-off read.
    fun keepScreenAwake(context: Context): Boolean = keepScreenAwake(prefs(context))

    // WO-25: malformed saved values must not silently prevent screen timeout.
    internal fun keepScreenAwake(prefs: SharedPreferences): Boolean =
        runCatching { prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, false) }.getOrDefault(false)

    // WO-25: changing screen timeout never requests a chip transaction.
    fun setKeepScreenAwake(context: Context, enabled: Boolean) = setKeepScreenAwake(prefs(context), enabled)

    // WO-25: preserve serving, chip verification and saved items when storing this choice.
    internal fun setKeepScreenAwake(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, enabled).apply()
    }

    // WO-2: missing or malformed chip settings cannot silently opt the phone into publication.
    fun chipMode(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_CHIP_MODE, false) }.getOrDefault(false)

    fun chipModeConfirmed(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_CHIP_CONFIRMED, false) }.getOrDefault(false)

    // WO-2: malformed or absent verification is unknown, never evidence that the chip is empty.
    fun lastVerifiedChip(context: Context): String? =
        runCatching { prefs(context).getString(KEY_VERIFIED_CHIP, null) }.getOrNull()

    // WO-2: pending replaces the old proof without prematurely displaying a failure warning.
    fun setChipPending(context: Context) {
        prefs(context).edit().putString(KEY_VERIFIED_CHIP, CHIP_PENDING).apply()
    }

    // WO-2: completed current-generation checks store proof or remove it on failure.
    fun setLastVerifiedChip(context: Context, verified: ByteArray?) {
        prefs(context).edit().putString(KEY_VERIFIED_CHIP, verified?.chipHex()).apply()
    }

    // WO-19: this private notification reflects cached detection rather than triggering another vendor call.
    internal fun setChipAvailable(context: Context, available: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHIP_AVAILABLE, available).apply()
    }

    // WO-19: only completed current-generation transactions reach this content-free persistence boundary.
    internal fun setChipOutcome(context: Context, outcome: ChipOutcome) = setChipOutcome(prefs(context), outcome)

    // WO-19: one editor replaces every metadata field and removes a previous fallback when none was attempted.
    internal fun setChipOutcome(prefs: SharedPreferences, outcome: ChipOutcome) {
        prefs.edit().putString(KEY_CHIP_WRITE_STATUS, outcome.goalStatus.name)
            .putString(KEY_CHIP_FALLBACK_STATUS, outcome.fallbackStatus?.name)
            .putInt(KEY_CHIP_GOAL_LENGTH, outcome.goalLength)
            .putInt(KEY_CHIP_READBACK_MATCHED, if (outcome.readBackMatched) CHIP_FLAG_TRUE else CHIP_FLAG_FALSE)
            .putInt(KEY_CHIP_VERIFIED_EMPTY, if (outcome.verifiedEmpty) CHIP_FLAG_TRUE else CHIP_FLAG_FALSE)
            .putInt(KEY_CHIP_READBACK_AVAILABLE, if (outcome.finalReadBackAvailable) CHIP_FLAG_TRUE else CHIP_FLAG_FALSE)
            .apply()
    }

    // WO-19: missing or malformed diagnostics must not change the authoritative verification state.
    internal fun lastChipOutcome(context: Context): ChipOutcome? = lastChipOutcome(prefs(context))

    // WO-19: read only the fixed status/number fields; private tag proof and saved items are not diagnostic inputs.
    internal fun lastChipOutcome(prefs: SharedPreferences): ChipOutcome? = runCatching {
        val values = prefs.all
        val status = ChipWriteStatus.valueOf(values[KEY_CHIP_WRITE_STATUS] as? String ?: return@runCatching null)
        val fallback = when (val raw = values[KEY_CHIP_FALLBACK_STATUS]) {
            null -> null
            is String -> ChipWriteStatus.valueOf(raw)
            else -> return@runCatching null
        }
        val length = (values[KEY_CHIP_GOAL_LENGTH] as? Int)?.takeIf { it >= 0 } ?: return@runCatching null
        fun flag(key: String): Boolean? = when (values[key]) {
            CHIP_FLAG_FALSE -> false
            CHIP_FLAG_TRUE -> true
            else -> null
        }
        ChipOutcome(status, fallback, length,
            flag(KEY_CHIP_READBACK_MATCHED) ?: return@runCatching null,
            flag(KEY_CHIP_VERIFIED_EMPTY) ?: return@runCatching null,
            flag(KEY_CHIP_READBACK_AVAILABLE) ?: return@runCatching null)
    }.getOrNull()

    // WO-2: JVM fakes exercise the same after-persistence trigger as the framework entry point.
    internal fun setEnabled(prefs: SharedPreferences, enabled: Boolean, requestSync: () -> Unit) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        requestSync()
    }

    // WO-2: confirmation and the requested mode persist together before the chip goal is recomputed.
    internal fun setChipMode(prefs: SharedPreferences, chipMode: Boolean, requestSync: () -> Unit) {
        val editor = prefs.edit().putBoolean(KEY_CHIP_MODE, chipMode)
        if (chipMode) editor.putBoolean(KEY_CHIP_CONFIRMED, true)
        editor.apply()
        requestSync()
    }

    // WO-3: toggling serving must leave the retained v0.1 URL and saved content untouched.
    fun setEnabled(context: Context, enabled: Boolean) {
        // WO-2: the global kill switch schedules its chip goal only after the flag is persisted.
        setEnabled(prefs(context), enabled) { ChipSync.request(context) }
    }

    // WO-2: both opting in and opting out recompute the goal after their setting is persisted.
    fun setChipMode(context: Context, chipMode: Boolean) {
        setChipMode(prefs(context), chipMode) { ChipSync.request(context) }
    }

    // WO-2: the retained legacy settings entry point cannot bypass the global serving trigger.
    fun save(context: Context, url: String, enabled: Boolean) {
        prefs(context).edit().putString(KEY_URL, url).apply()
        setEnabled(context, enabled)
    }

    fun listen(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unlisten(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

// WO-7: sanitize at the session boundary before either logging or persisting any response.
internal class TapTrace(
    private val maxLines: Int, // WO-7: retain the existing bounded trace history.
    private val maxHex: Int, // WO-7: retain the existing bound on non-sensitive APDU hex.
    private val log: (String) -> Unit, // WO-7: logs receive only the already-sanitized line.
    private val persist: (String) -> Unit, // WO-7: preference writes share the same sanitized text.
) {
    private val lines = mutableListOf<String>() // WO-7: raw response bytes are never retained here.
    private var wifiSession = false // WO-7: sensitivity belongs to the file captured at application SELECT.

    // WO-7: the same saved-item snapshot determines both served bytes and trace sensitivity.
    fun snapshot(state: ItemState, enabled: Boolean): ByteArray? {
        resetSession()
        return state.ndefFile(enabled)?.also { wifiSession = state.activeItem is TagItem.Wifi }
    }

    // WO-7: a refused selection or deactivation cannot retain an earlier session's sensitivity.
    fun resetSession() {
        wifiSession = false
    }

    // WO-7: mask the complete READ BINARY reply before it can become hex or reach either sink.
    fun record(command: ByteArray, response: ByteArray) {
        val reply = if (wifiSession && command.size > 1 && command[1] == INS_READ_BINARY) {
            "<${response.size} bytes>"
        } else response.toHex(maxHex)
        val line = "> ${command.toHex(maxHex)}  < $reply"
        log(line)
        if (lines.size < maxLines) lines.add(line)
        persist("Tap in progress\n" + lines.joinToString("\n"))
    }

    // WO-7: final persistence reuses sanitized lines instead of rebuilding raw APDU data.
    fun deactivate(time: String, reason: Int) {
        if (lines.isNotEmpty()) {
            persist("Last tap $time (deactivated: $reason)\n" + lines.joinToString("\n"))
            lines.clear()
        }
        resetSession()
    }

    private companion object {
        private val INS_READ_BINARY: Byte = 0xB0.toByte() // WO-7: only this instruction returns credential data.
    }
}

/**
 * Answers the reader's Type 4 Tag APDUs. Android routes APDUs here after the reader selects
 * the NDEF application AID declared in res/xml/apduservice.xml.
 */
class NdefHostApduService : HostApduService() {
    private var servedItemId: String? = null // WO-12: the count follows the item snapshotted at SELECT.
    private val tag = Type4Tag(::currentNdefFile, ::readCompleted) // WO-12: observe completed NDEF delivery.

    // WO-3: read selected content at application SELECT; unavailable content answers 6A82.
    private fun currentNdefFile(): ByteArray? {
        // WO-12: a refused selection must not retain an earlier item's count destination.
        servedItemId = null
        trace.resetSession() // WO-7: unavailable content cannot inherit an earlier Wi-Fi session flag.
        if (!TagPrefs.enabled(this)) return null
        return try {
            // WO-12: snapshot identity and encoded content from the same saved-item state.
            val state = ItemStore(this).load()
            trace.snapshot(state, enabled = true)?.also { servedItemId = state.activeItemId }
        } catch (_: Exception) {
            null
        }
    }

    // WO-12: counting failure cannot alter the reader's APDU response.
    private fun readCompleted() {
        val id = servedItemId ?: return
        try {
            ItemStore(this).incrementTapCount(id)
        } catch (_: Exception) {
            Log.w(LOG_TAG, "Completed read count could not be saved.")
        }
    }

    // WO-7: both diagnostic sinks use the same session-aware sanitizer.
    private val trace = TapTrace(MAX_TRACE_LINES, MAX_TRACE_HEX,
        log = { Log.d(LOG_TAG, it) }, persist = { TagPrefs.saveTrace(this, it) })

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return Type4Constants.SW_WRONG_P1P2
        val response = tag.process(commandApdu)
        record(commandApdu, response)
        return response
    }

    override fun onDeactivated(reason: Int) {
        tag.reset()
        // WO-12: deactivation releases the identity bound to the completed-read session.
        servedItemId = null
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        trace.deactivate(time, reason) // WO-7: a finished trace contains no raw Wi-Fi READ BINARY replies.
    }

    private fun record(command: ByteArray, response: ByteArray) {
        trace.record(command, response) // WO-7: redaction happens before logging and incremental persistence.
    }

    private companion object {
        const val LOG_TAG = "PocketTag"
        const val MAX_TRACE_LINES = 12
        // Hex chars per APDU kept in the trace; enough to see the instruction and the file.
        const val MAX_TRACE_HEX = 28
    }
}

private fun ByteArray.toHex(maxChars: Int): String {
    val hex = joinToString("") { "%02X".format(it) }
    return if (hex.length <= maxChars) hex else hex.take(maxChars) + "…(${size}B)"
}
