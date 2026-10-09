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
import android.view.WindowManager
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
    private lateinit var keepScreenAwakeSwitch: Switch // WO-25: normal-screen timeout has its own saved choice.
    private var renderingKeepScreenAwakeSwitch = false // WO-25: reflecting preferences must not persist them again.
    private var resumed = false // WO-25: queued preference refreshes must not keep a paused screen awake.
    private lateinit var chipSwitch: Switch // WO-2: optional chip publication is separate from global serving.
    private lateinit var chipRetry: Button // WO-2: unknown controller outcomes retain a recovery action.
    private var renderingChipSwitch = false // WO-2: reflecting saved state must not publish or confirm it again.
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
            // WO-2: verification and switch changes refresh the chip status without rewriting saved content.
            // WO-19: cached capability and completed metadata must refresh the switch and RF guidance.
            if (key == TagPrefs.KEY_CHIP_MODE || key == TagPrefs.KEY_ENABLED ||
                key == TagPrefs.KEY_VERIFIED_CHIP || key == TagPrefs.KEY_CHIP_AVAILABLE ||
                key == TagPrefs.KEY_CHIP_WRITE_STATUS) {
                refreshChipSwitch()
                refreshStatus(null)
            }
            // WO-25: apply this preference without changing serving or chip publication.
            if (key == TagPrefs.KEY_KEEP_SCREEN_AWAKE) refreshKeepScreenAwake()
            refreshTrace()
        } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // WO-3: the selected content replaces the former single URL field.
        items = findViewById(R.id.items)
        enabledSwitch = findViewById(R.id.enabled)
        // WO-25: bind and render before attaching the persistence listener.
        keepScreenAwakeSwitch = findViewById(R.id.keep_screen_awake)
        keepScreenAwakeSwitch.isChecked = TagPrefs.keepScreenAwake(this)
        // WO-2: hidden-by-default controls become available only after content API detection.
        chipSwitch = findViewById(R.id.chip_mode)
        chipRetry = findViewById(R.id.chip_retry)
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
        // WO-25: user changes update timeout immediately; rendering never writes the preference.
        keepScreenAwakeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!renderingKeepScreenAwakeSwitch) {
                TagPrefs.setKeepScreenAwake(this, checked)
                refreshKeepScreenAwake()
            }
        }
        // WO-2: canceling the first-enable warning leaves chip mode off without requesting a write.
        chipSwitch.setOnCheckedChangeListener { _, checked ->
            if (!renderingChipSwitch) {
                if (checked && !TagPrefs.chipModeConfirmed(this)) {
                    refreshChipSwitch()
                    AlertDialog.Builder(this).setTitle(R.string.chip_confirm_title)
                        .setMessage(R.string.chip_confirm_message)
                        .setPositiveButton(R.string.chip_confirm) { _, _ ->
                            TagPrefs.setChipMode(this, true)
                            refreshChipSwitch()
                            refreshStatus(null)
                        }.setNegativeButton(android.R.string.cancel, null).show()
                } else {
                    TagPrefs.setChipMode(this, checked)
                    refreshStatus(null)
                }
            }
        }
        chipRetry.setOnClickListener { ChipSync.retry(this) }
        refreshChipSwitch()

        val diagnostics = findViewById<Switch>(R.id.diagnostics)
        diagnostics.isChecked = TagPrefs.showTrace(this)
        diagnostics.setOnCheckedChangeListener { _, checked ->
            TagPrefs.setShowTrace(this, checked)
            refreshTrace()
        }
        // WO-2: startup also empties stale controller content when chip mode and Serve tag are off.
        ChipSync.reconcile(this)
    }

    override fun onResume() {
        super.onResume()
        // WO-25: only the resumed normal screen may apply the saved awake choice.
        resumed = true
        // While the app is open, claim the NDEF AID even if another app also registered it.
        // WO-15: an unavailable preferred route must not prevent opening the selector.
        runCatching { cardEmulation()?.setPreferredService(this, service) }
        TagPrefs.listen(this, prefsListener)
        refreshKeepScreenAwake()
        // WO-3: edits return through onResume, including deletion of the last item.
        refreshItems()
        // WO-2: returning from another screen reflects the persisted opt-in without a new write.
        refreshChipSwitch()
        refreshStatus(null)
        refreshTrace()
    }

    // WO-2: programmatic rendering never invokes the opt-in or opt-out persistence trigger.
    private fun refreshChipSwitch() {
        renderingChipSwitch = true
        chipSwitch.visibility = if (ChipSync.available(this)) View.VISIBLE else View.GONE
        chipSwitch.isChecked = TagPrefs.chipMode(this)
        renderingChipSwitch = false
    }

    // WO-25: saved state controls only this window, including refreshes queued before a pause.
    private fun refreshKeepScreenAwake() {
        val enabled = TagPrefs.keepScreenAwake(this)
        renderingKeepScreenAwakeSwitch = true
        keepScreenAwakeSwitch.isChecked = enabled
        renderingKeepScreenAwakeSwitch = false
        if (mainScreenAwake(resumed, enabled)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onPause() {
        // WO-25: clear before any cleanup so a late refresh cannot restore the awake flag.
        resumed = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        // WO-2: global off has priority and requires fresh, exact EMPTY verification on capable phones.
        val chipAvailable = ChipSync.available(this)
        val serving = TagPrefs.enabled(this)
        val chipMode = chipAvailable && TagPrefs.chipMode(this)
        val chipDisplay = if (chipAvailable) ChipSync.display(this, savedItems) else null
        val chipMessage = chipDisplay?.let { ChipSync.message(this, it, selected?.label.orEmpty()) }
        val state = when {
            chipAvailable && !serving -> chipMessage
            !savedItems.readable -> getString(R.string.status_unreadable)
            selected == null -> getString(R.string.status_no_item)
            adapter == null -> getString(R.string.status_no_nfc)
            !hce -> getString(R.string.status_no_hce)
            !adapter.isEnabled -> getString(R.string.status_nfc_off)
            !serving -> getString(R.string.status_paused)
            // WO-2: controller-served content is reported from verification rather than HCE routing.
            chipMode -> chipMessage
            // WO-3: identify the selected content by its saved label.
            else -> getString(R.string.status_serving, selected.label)
        }
        // WO-2: an HCE routing summary cannot describe a chip-mode response or certify the kill switch.
        val routing = if (chipMode || (chipAvailable && !serving)) null else cardEmulation()?.let {
            val isDefault = it.isDefaultServiceForAid(service, Type4Constants.NDEF_AID_HEX)
            getString(if (isDefault) R.string.routing_ok else R.string.routing_other)
        }
        // WO-2: even with chip mode off, any unknown residual content remains visible with Retry.
        val chipDetails = if (chipAvailable && serving && state != chipMessage) chipMessage else null
        status.text = listOfNotNull(prefix, state, chipDetails, routing).joinToString("\n")
        // WO-19: an RF-failed item exposes the Retry action named by its appended recovery hint.
        chipRetry.visibility = if (ChipSync.retryAvailable(this, chipDisplay)) View.VISIBLE else View.GONE
    }

    // WO-19: the existing diagnostics toggle also shows the content-free vendor outcome on supported phones.
    private fun refreshTrace() {
        val show = TagPrefs.showTrace(this)
        trace.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val chip = ChipSync.diagnostics(this)
            val reader = TagPrefs.lastTrace(this) ?: getString(R.string.trace_none)
            trace.text = listOfNotNull(chip, reader).joinToString("\n")
        }
    }
}

// WO-25: a saved opt-in cannot prevent timeout after the normal screen has paused.
internal fun mainScreenAwake(resumed: Boolean, enabled: Boolean): Boolean = resumed && enabled

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
