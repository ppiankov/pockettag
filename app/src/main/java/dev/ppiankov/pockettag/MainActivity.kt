package dev.ppiankov.pockettag

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView

// WO-3: select a saved item while retaining serving, routing, and tap diagnostics.
class MainActivity : Activity() {
    private lateinit var items: LinearLayout // WO-3: stored-order item rows.
    private val store by lazy { ItemStore(this) } // WO-3: shared selection and content.
    private lateinit var enabledSwitch: Switch
    private lateinit var status: TextView
    private lateinit var trace: TextView

    private val service by lazy { ComponentName(this, NdefHostApduService::class.java) }
    // WO-3: content changes refresh selection; APDU trace writes only refresh diagnostics.
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key -> runOnUiThread {
            // WO-12: a completed read changes row counts without changing the selected content.
            if (key == ItemStore.ITEMS_KEY || key == ItemStore.ACTIVE_ID_KEY ||
                key == ItemStore.TAP_COUNTS_KEY) {
                refreshItems()
                refreshStatus(null)
            }
            refreshTrace()
        } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // WO-3: the selected content replaces the former single URL field.
        items = findViewById(R.id.items)
        enabledSwitch = findViewById(R.id.enabled)
        status = findViewById(R.id.status)
        trace = findViewById(R.id.trace)

        enabledSwitch.isChecked = TagPrefs.enabled(this)

        // WO-3: toggle serving without replacing the retained legacy URL.
        findViewById<Button>(R.id.add_item).setOnClickListener { addItem() }
        // WO-15: booth mode shares the saved selection without changing it.
        findViewById<Button>(R.id.booth_mode).setOnClickListener {
            startActivity(Intent(this, BoothActivity::class.java))
        }
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            TagPrefs.setEnabled(this, checked)
            refreshStatus(null)
        }

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
        // WO-15: an unavailable preferred route must not prevent opening the selector.
        runCatching { cardEmulation()?.setPreferredService(this, service) }
        TagPrefs.listen(this, prefsListener)
        // WO-3: edits return through onResume, including deletion of the last item.
        refreshItems()
        refreshStatus(null)
        refreshTrace()
    }

    override fun onPause() {
        // WO-15: routing failures must not interrupt normal activity cleanup.
        runCatching { cardEmulation()?.unsetPreferredService(this) }
        TagPrefs.unlisten(this, prefsListener)
        super.onPause()
    }

    // WO-3: creation chooses a type once; editing never exposes a type picker.
    private fun addItem() {
        // WO-3: the migration-only Saved link type is never offered for creation.
        val types = TagItem.Type.creatableTypes
        AlertDialog.Builder(this).setTitle(R.string.add_item_title)
            .setItems(types.map { getString(it.titleResource()) }.toTypedArray()) { _, index ->
                startActivity(Intent(this, EditItemActivity::class.java)
                    .putExtra(EditItemActivity.EXTRA_TYPE, types[index].storageName))
            }.show()
    }

    // WO-3: every row reflects persisted selection and supports tap/select or hold/edit.
    private fun refreshItems() {
        val state = store.load()
        // WO-12: load counters once for the current stored-order list.
        val counts = store.tapCounts()
        items.removeAllViews()
        state.items.forEach { item ->
            items.addView(RadioButton(this).apply {
                text = getString(R.string.item_row, item.label, getString(item.type.titleResource()))
                // WO-12: zero counts do not add noise to a newly created item's row.
                val count = counts[item.id] ?: 0
                if (count > 0) append("\n" + resources.getQuantityString(R.plurals.tap_count, count, count))
                isChecked = item.id == state.activeItemId
                setOnClickListener {
                    store.select(item.id)
                    refreshItems()
                    refreshStatus(null)
                }
                setOnLongClickListener {
                    startActivity(Intent(this@MainActivity, EditItemActivity::class.java)
                        .putExtra(EditItemActivity.EXTRA_ID, item.id))
                    true
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // WO-3: report saved-data read failures before ordinary NFC availability.
    private fun refreshStatus(prefix: String?) {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        val hce = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
        // WO-3: unreadable storage disables creation and takes precedence over empty-selection status.
        val savedItems = store.load()
        findViewById<Button>(R.id.add_item).isEnabled = savedItems.readable
        val selected = savedItems.activeItem
        val state = when {
            !savedItems.readable -> getString(R.string.status_unreadable)
            selected == null -> getString(R.string.status_no_item)
            adapter == null -> getString(R.string.status_no_nfc)
            !hce -> getString(R.string.status_no_hce)
            !adapter.isEnabled -> getString(R.string.status_nfc_off)
            !TagPrefs.enabled(this) -> getString(R.string.status_paused)
            // WO-3: identify the selected content by its saved label.
            else -> getString(R.string.status_serving, selected.label)
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

// WO-15: both foreground screens share the same guarded HCE lookup.
internal fun Context.cardEmulation(): CardEmulation? = try {
    val adapter = NfcAdapter.getDefaultAdapter(this)
    if (adapter == null ||
        !packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
        null
    } else {
        CardEmulation.getInstance(adapter)
    }
} catch (_: Exception) {
    // WO-15: a device routing exception leaves the screen usable.
    null
}
