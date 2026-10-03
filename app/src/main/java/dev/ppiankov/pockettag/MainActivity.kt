package dev.ppiankov.pockettag

import android.app.Activity
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/** One screen: the URL, an on/off switch, a status line, and the last reader exchange. */
class MainActivity : Activity() {
    private lateinit var urlField: EditText
    private lateinit var enabledSwitch: Switch
    private lateinit var status: TextView
    private lateinit var trace: TextView

    private val service by lazy { ComponentName(this, NdefHostApduService::class.java) }
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { refreshTrace() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        urlField = findViewById(R.id.url)
        enabledSwitch = findViewById(R.id.enabled)
        status = findViewById(R.id.status)
        trace = findViewById(R.id.trace)

        urlField.setText(TagPrefs.url(this))
        enabledSwitch.isChecked = TagPrefs.enabled(this)

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        enabledSwitch.setOnCheckedChangeListener { _, _ -> save() }

        val diagnostics = findViewById<Switch>(R.id.diagnostics)
        diagnostics.isChecked = TagPrefs.showTrace(this)
        diagnostics.setOnCheckedChangeListener { _, checked ->
            TagPrefs.setShowTrace(this, checked)
            refreshTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        // While the app is open, claim the NDEF AID even if another app also registered it.
        cardEmulation()?.setPreferredService(this, service)
        TagPrefs.listen(this, prefsListener)
        refreshStatus(null)
        refreshTrace()
    }

    override fun onPause() {
        cardEmulation()?.unsetPreferredService(this)
        TagPrefs.unlisten(this, prefsListener)
        super.onPause()
    }

    private fun cardEmulation(): CardEmulation? {
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return null
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            return null
        }
        return CardEmulation.getInstance(adapter)
    }

    private fun save() {
        val url = urlField.text.toString().trim()
        if (url.isEmpty()) {
            refreshStatus(getString(R.string.error_empty))
            return
        }
        try {
            NdefMessage.ndefFile(url)
        } catch (e: UrlTooLongException) {
            refreshStatus(getString(R.string.error_too_long))
            return
        }
        TagPrefs.save(this, url, enabledSwitch.isChecked)
        refreshStatus(getString(R.string.saved))
    }

    private fun refreshStatus(prefix: String?) {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        val hce = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
        val state = when {
            adapter == null -> getString(R.string.status_no_nfc)
            !hce -> getString(R.string.status_no_hce)
            !adapter.isEnabled -> getString(R.string.status_nfc_off)
            !TagPrefs.enabled(this) -> getString(R.string.status_paused)
            else -> getString(R.string.status_serving, TagPrefs.url(this))
        }
        val routing = cardEmulation()?.let {
            val isDefault = it.isDefaultServiceForAid(service, Type4Constants.NDEF_AID_HEX)
            getString(if (isDefault) R.string.routing_ok else R.string.routing_other)
        }
        status.text = listOfNotNull(prefix, state, routing).joinToString("\n")
    }

    private fun refreshTrace() {
        val show = TagPrefs.showTrace(this)
        trace.visibility = if (show) View.VISIBLE else View.GONE
        if (show) trace.text = TagPrefs.lastTrace(this) ?: getString(R.string.trace_none)
    }
}
