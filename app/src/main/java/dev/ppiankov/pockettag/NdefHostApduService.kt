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
    internal const val KEY_CHIP_MODE = "chip_mode" // WO-2: chip publication remains an explicit opt-in.
    internal const val KEY_VERIFIED_CHIP = "last_verified_chip" // WO-2: only byte-exact read-back is remembered.
    private const val KEY_CHIP_CONFIRMED = "chip_mode_confirmed" // WO-2: retain the first-enable acknowledgement.
    private const val KEY_TRACE = "last_trace"
    private const val KEY_SHOW_TRACE = "show_trace"
    const val DEFAULT_URL = "https://obstalabs.dev"

    // The trace is always recorded; this only controls whether the screen shows it.
    fun showTrace(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_TRACE, false)

    fun setShowTrace(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_TRACE, show).apply()
    }

    // Last reader exchange, shown on screen so a failed tap can be diagnosed without adb.
    fun lastTrace(context: Context): String? = prefs(context).getString(KEY_TRACE, null)

    fun saveTrace(context: Context, trace: String) {
        prefs(context).edit().putString(KEY_TRACE, trace).apply()
    }

    fun url(context: Context): String =
        prefs(context).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    // WO-2: missing or malformed chip settings cannot silently opt the phone into publication.
    fun chipMode(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_CHIP_MODE, false) }.getOrDefault(false)

    fun chipModeConfirmed(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_CHIP_CONFIRMED, false) }.getOrDefault(false)

    // WO-2: malformed or absent verification is unknown, never evidence that the chip is empty.
    fun lastVerifiedChip(context: Context): String? =
        runCatching { prefs(context).getString(KEY_VERIFIED_CHIP, null) }.getOrNull()

    // WO-2: new requests remove the old proof; current-generation read-backs replace it atomically.
    fun setLastVerifiedChip(context: Context, verified: ByteArray?) {
        prefs(context).edit().putString(KEY_VERIFIED_CHIP, verified?.chipHex()).apply()
    }

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
        if (!TagPrefs.enabled(this)) return null
        return try {
            // WO-12: snapshot identity and encoded content from the same saved-item state.
            val state = ItemStore(this).load()
            state.ndefFile(enabled = true)?.also { servedItemId = state.activeItemId }
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

    private val trace = mutableListOf<String>()

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
        if (trace.isNotEmpty()) {
            val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
            TagPrefs.saveTrace(this, "Last tap $time (deactivated: $reason)\n" + trace.joinToString("\n"))
            trace.clear()
        }
    }

    private fun record(command: ByteArray, response: ByteArray) {
        val line = "> ${command.toHex(MAX_TRACE_HEX)}  < ${response.toHex(MAX_TRACE_HEX)}"
        Log.d(LOG_TAG, line)
        if (trace.size < MAX_TRACE_LINES) trace.add(line)
        // Persist as we go: a reader that drops the field early may never trigger onDeactivated.
        TagPrefs.saveTrace(this, "Tap in progress\n" + trace.joinToString("\n"))
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
