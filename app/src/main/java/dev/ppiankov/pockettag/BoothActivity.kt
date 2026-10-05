package dev.ppiankov.pockettag

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

// WO-15: keep the selected content, readiness, and completed reads visible at a stand.
class BoothActivity : Activity() {
    private val store by lazy { ItemStore(this) } // WO-15: use the existing saved selection.
    private val service by lazy { ComponentName(this, NdefHostApduService::class.java) } // WO-15: existing HCE route.
    private lateinit var label: TextView // WO-15: selected item's large label.
    private lateinit var status: TextView // WO-15: current readiness or warning.
    private lateinit var count: TextView // WO-15: selected item's completed-read count.
    private lateinit var sent: TextView // WO-15: brief feedback for a new completed read.
    private lateinit var nfcSettings: Button // WO-15: recovery action for disabled NFC.
    private var resumed = false // WO-15: ignore callbacks after the screen becomes inactive.
    private var lastItemId: String? = null // WO-15: switching items must not look like a tap.
    private var lastCount = 0 // WO-15: compare only counts observed while this screen is visible.
    private var lastSentAt: Long? = null // WO-18: retain confirmation until its visible-read baseline changes.

    // WO-15: diagnostics writes do not affect booth content or readiness.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == ItemStore.ITEMS_KEY || key == ItemStore.ACTIVE_ID_KEY ||
            key == TagPrefs.KEY_ENABLED || key == ItemStore.TAP_COUNTS_KEY) {
            runOnUiThread { if (resumed) refresh() }
        }
    }

    // WO-15: NFC can change in quick settings without recreating this activity.
    private val nfcReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (resumed && intent?.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_booth)
        label = findViewById(R.id.booth_label)
        status = findViewById(R.id.booth_status)
        count = findViewById(R.id.booth_count)
        sent = findViewById(R.id.booth_sent)
        nfcSettings = findViewById(R.id.booth_nfc_settings)
        nfcSettings.setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // WO-15: vendor routing failures must leave the booth screen working.
        runCatching { cardEmulation()?.setPreferredService(this, service) }
        // WO-15: establish a fresh baseline so reads received while hidden do not flash Sent.
        refresh(flashChanges = false)
        TagPrefs.listen(this, prefsListener)
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nfcReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(nfcReceiver, filter)
        }
    }

    override fun onPause() {
        resumed = false
        TagPrefs.unlisten(this, prefsListener)
        unregisterReceiver(nfcReceiver)
        // WO-18: reentering booth mode cannot display a confirmation from the previous visit.
        lastSentAt = null
        sent.text = ""
        sent.visibility = View.INVISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // WO-15: cleanup must complete even when the device rejects the preferred route.
        runCatching { cardEmulation()?.unsetPreferredService(this) }
        super.onPause()
    }

    // WO-15: all event sources render the same storage, radio, serving, and encoding state.
    private fun refresh(flashChanges: Boolean = true) {
        val savedItems = store.load()
        val selected = savedItems.activeItem
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(this) }.getOrNull()
        val state = boothStatus(
            readable = savedItems.readable,
            hasItem = selected != null,
            nfcAvailable = adapter != null,
            hceAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION),
            nfcEnabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false),
            serving = TagPrefs.enabled(this),
            itemEncodes = savedItems.ndefFile(enabled = true) != null,
        )
        label.text = selected?.label.orEmpty()
        status.text = when (state) {
            BoothStatus.UNREADABLE -> getString(R.string.status_unreadable)
            BoothStatus.NO_ITEM -> getString(R.string.status_no_item)
            BoothStatus.NO_NFC -> getString(R.string.status_no_nfc)
            BoothStatus.NO_HCE -> getString(R.string.status_no_hce)
            BoothStatus.NFC_OFF -> getString(R.string.status_nfc_off)
            BoothStatus.PAUSED -> getString(R.string.status_paused)
            BoothStatus.ITEM_INVALID -> getString(R.string.status_item_invalid)
            BoothStatus.READY -> getString(R.string.status_serving, selected?.label.orEmpty())
        }
        nfcSettings.visibility = if (state == BoothStatus.NFC_OFF) View.VISIBLE else View.GONE

        val currentCount = selected?.let { store.tapCounts()[it.id] } ?: 0
        count.text = resources.getQuantityString(R.plurals.tap_count, currentCount, currentCount)
        // WO-15: selection changes, resets, and resumes clear feedback instead of inventing a tap.
        // WO-18: retain only timestamps for count increases observed with the same visible selection.
        lastSentAt = boothSentAt(lastItemId, lastCount, selected?.id, currentCount,
            flashChanges, lastSentAt, System.currentTimeMillis())
        sent.text = lastSentAt?.let {
            getString(R.string.booth_sent_at, DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it)))
        }.orEmpty()
        sent.visibility = if (lastSentAt == null) View.INVISIBLE else View.VISIBLE
        lastItemId = selected?.id
        lastCount = currentCount
    }

}

// WO-18: resume, selection changes, and count resets cannot masquerade as a newly completed read.
internal fun boothSentAt(
    previousItemId: String?,
    previousCount: Int,
    currentItemId: String?,
    currentCount: Int,
    observeRead: Boolean,
    previousSentAt: Long?,
    now: Long,
): Long? = when {
    !observeRead || currentItemId != previousItemId || currentCount < previousCount -> null
    currentItemId != null && currentCount > previousCount -> now
    else -> previousSentAt
}
