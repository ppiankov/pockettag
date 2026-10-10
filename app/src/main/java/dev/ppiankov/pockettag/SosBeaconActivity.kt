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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

// WO-28: a private Open-only signal shows over the keyguard without granting access to the phone.
class SosBeaconActivity : Activity() {
    private val service by lazy { ComponentName(this, NdefHostApduService::class.java) } // WO-28: same preferred HCE route as Booth.
    private lateinit var root: View // WO-28: one surface owns background phases and stale-policy hiding.
    private lateinit var ownerLine: TextView // WO-28: empty until the owner deliberately enters public text.
    private lateinit var status: TextView // WO-28: readiness follows the actual SOS item and existing chip proof.
    private lateinit var nfcSettings: Button // WO-28: disabled NFC retains the existing recovery action.
    private lateinit var chipRetry: Button // WO-28: failed chip verification retains the existing recovery action.
    private var resumed = false // WO-28: late callbacks must not reactivate a hidden window or route.
    private var observing = false // WO-28: an early rejected launch still cleans up its policy listener.
    private var receiverRegistered = false // WO-28: Power is observed across pause until destruction.
    private var previousBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE // WO-28: restore only this window's brightness.
    private var style = SosBeaconStyle.STEADY // WO-28: never pulse until saved settings explicitly select it.
    private var bright = true // WO-28: each new visible session starts on the readable red phase.
    private var pulsing = false // WO-28: unrelated NFC changes must not schedule additional pulses.
    private val handler = Handler(Looper.getMainLooper()) // WO-28: display phases and lifecycle cleanup share the UI thread.

    // WO-28: paused windows can still be visible; Covert or Off must hide them immediately.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in SosPrefs.KEYS || key in SosBeaconPrefs.KEYS ||
            key == ItemStore.ITEMS_KEY || key == ItemStore.ACTIVE_ID_KEY ||
            key == TagPrefs.KEY_ENABLED || key == TagPrefs.KEY_CHIP_MODE ||
            key == TagPrefs.KEY_VERIFIED_CHIP || key == TagPrefs.KEY_CHIP_AVAILABLE ||
            key == TagPrefs.KEY_CHIP_WRITE_STATUS) {
            runOnUiThread {
                if (!beaconAvailable(SosPrefs(this).load())) {
                    if (::root.isInitialized) root.visibility = View.INVISIBLE
                    finish()
                } else if (resumed && !isFinishing) refresh()
            }
        }
    }

    // WO-28: one delayed callback changes the background at most once per second.
    private val pulse = object : Runnable {
        override fun run() {
            if (!resumed || isFinishing || !pulsing || style != SosBeaconStyle.PULSE) return
            bright = !bright
            root.setBackgroundColor(beaconBackground(style, bright))
            handler.postDelayed(this, SOS_PULSE_PHASE_MS)
        }
    }

    // WO-28: legacy lock-screen entry may pause once; Power still finishes without touching SOS.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> finish()
                NfcAdapter.ACTION_ADAPTER_STATE_CHANGED -> if (resumed && !isFinishing) refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WO-28: observe before reading eligibility so a concurrent Off/Covert change cannot be missed.
        TagPrefs.listen(this, prefsListener)
        observing = true
        if (!beaconAvailable(SosPrefs(this).load())) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContentView(R.layout.activity_sos_beacon)
        root = findViewById(R.id.sos_beacon_root)
        // WO-28: paint the steady default before the first frame without a second theme background.
        window.setBackgroundDrawable(null)
        root.setBackgroundColor(SOS_BEACON_RED)
        ownerLine = findViewById(R.id.sos_beacon_owner)
        status = findViewById(R.id.sos_beacon_status)
        nfcSettings = findViewById(R.id.sos_beacon_nfc_settings)
        nfcSettings.setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        chipRetry = findViewById(R.id.sos_beacon_chip_retry)
        chipRetry.setOnClickListener { ChipSync.retry(this) }
        findViewById<Button>(R.id.sos_beacon_exit).setOnClickListener { finish() }
        previousBrightness = window.attributes.screenBrightness
        applyInsets()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // WO-28: both actions are protected; NFC's own system-app UID needs exported delivery.
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (!beaconAvailable(SosPrefs(this).load())) {
            root.visibility = View.INVISIBLE
            finish()
            return
        }
        resumed = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        // WO-28: vendor routing failure must not prevent showing instructions or cleaning up later.
        runCatching { cardEmulation()?.setPreferredService(this, service) }
        refresh()
    }

    override fun onPause() {
        resumed = false
        stopPulse()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
        runCatching { cardEmulation()?.unsetPreferredService(this) }
        // WO-28: modern Power broadcasts can be deferred; pause must prevent automatic return.
        // WO-28: API 24–26 use the receiver because legacy lock-screen entry can itself pause.
        if (beaconFinishesOnPause(Build.VERSION.SDK_INT)) finish()
        super.onPause()
    }

    override fun onDestroy() {
        stopPulse()
        if (observing) TagPrefs.unlisten(this, prefsListener)
        if (receiverRegistered) unregisterReceiver(receiver)
        super.onDestroy()
    }

    // WO-28: target 35 uses edge-to-edge; Exit must remain clear of system bars and cutouts.
    private fun applyInsets() {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            } else {
                view.setPadding(left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop,
                    right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom)
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun stopPulse() {
        pulsing = false
        handler.removeCallbacks(pulse)
    }

    // WO-28: status refreshes do not restart a pulse or replace the SOS serving rule.
    private fun refresh() {
        val sos = SosPrefs(this).load()
        if (!beaconAvailable(sos)) {
            root.visibility = View.INVISIBLE
            finish()
            return
        }
        val settings = SosBeaconPrefs(this).load()
        if (settings.style != style) {
            stopPulse()
            style = settings.style
            bright = true
        }
        ownerLine.text = settings.ownerLine
        ownerLine.visibility = if (settings.ownerLine.isEmpty()) View.GONE else View.VISIBLE
        root.setBackgroundColor(beaconBackground(style, bright))
        if (style == SosBeaconStyle.PULSE && !pulsing) {
            pulsing = true
            handler.postDelayed(pulse, SOS_PULSE_PHASE_MS)
        }
        val items = ItemStore(this).load()
        val enabled = TagPrefs.enabled(this)
        val item = servedItem(items, enabled, sos)
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(this) }.getOrNull()
        val chipAvailable = ChipSync.available(this)
        val chipMode = chipAvailable && TagPrefs.chipMode(this)
        val display = if (chipAvailable) ChipSync.display(this, items) else null
        val readiness = boothStatus(items.readable, item != null, adapter != null,
            packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION),
            runCatching { adapter?.isEnabled == true }.getOrDefault(false),
            enabled || sos.enabled, servedNdefFile(item) != null)
        status.text = when (readiness) {
            BoothStatus.UNREADABLE -> getString(R.string.status_unreadable)
            BoothStatus.NO_ITEM -> getString(R.string.sos_item_unavailable)
            BoothStatus.NO_NFC -> getString(R.string.status_no_nfc)
            BoothStatus.NO_HCE -> getString(R.string.status_no_hce)
            BoothStatus.NFC_OFF -> getString(R.string.status_nfc_off)
            BoothStatus.PAUSED -> getString(R.string.status_paused)
            BoothStatus.ITEM_INVALID -> getString(R.string.status_item_invalid)
            BoothStatus.READY -> getString(R.string.status_serving, item?.label.orEmpty())
        }
        if (chipMode) {
            val message = ChipSync.message(this, display!!, item?.label.orEmpty())
            if (readiness == BoothStatus.READY) status.text = message else status.append("\n" + message)
        } else if (display == ChipDisplay.PENDING || display == ChipDisplay.UNKNOWN) {
            status.append("\n" + ChipSync.message(this, display, item?.label.orEmpty()))
        }
        sosStatus(this, sos, enabled, chipMode)?.let { status.append("\n" + it) }
        chipRetry.visibility = if (ChipSync.retryAvailable(this, display)) View.VISIBLE else View.GONE
        nfcSettings.visibility = if (readiness == BoothStatus.NFC_OFF) View.VISIBLE else View.GONE
    }
}
