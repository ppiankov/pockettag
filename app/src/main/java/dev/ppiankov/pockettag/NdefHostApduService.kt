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
    private const val KEY_ENABLED = "enabled"
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

    fun save(context: Context, url: String, enabled: Boolean) {
        prefs(context).edit().putString(KEY_URL, url).putBoolean(KEY_ENABLED, enabled).apply()
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
    private val tag = Type4Tag(::currentNdefFile)

    // Read on every application SELECT so edits in the app apply on the next tap.
    private fun currentNdefFile(): ByteArray? {
        if (!TagPrefs.enabled(this)) return null
        return try {
            NdefMessage.ndefFile(TagPrefs.url(this))
        } catch (e: UrlTooLongException) {
            null
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
