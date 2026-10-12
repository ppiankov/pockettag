package dev.ppiankov.pockettag

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

// WO-22: only Android's permission-protected tile service can toggle SOS outside the app.
class SosTileService : TileService() {
    private var listening = false // WO-22: repeated bindings must not register duplicate observers.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in SosPrefs.KEYS) render()
    } // WO-22: mode changes immediately replace visible and accessibility labels.

    override fun onStartListening() {
        super.onStartListening()
        if (!listening) {
            TagPrefs.listen(this, listener)
            listening = true
        }
        render()
    }

    override fun onStopListening() {
        if (listening) TagPrefs.unlisten(this, listener)
        listening = false
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = SosPrefs(this).load()
        if (!SosController.setEnabled(this, !state.enabled)) {
            // WO-22: configuring a contact uses Android's security challenge, never a keyguard bypass.
            unlockAndRun { openMain() }
            return
        }
        render()
        if (sosPermissionRequest(SosPrefs(this).load(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)) {
            // WO-22: serving is already on; only the first Open permission dialog needs an activity.
            unlockAndRun { openMain() }
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = SosPrefs(this).load()
        val label = getString(sosPresentation(state, notificationsAllowed = false).tileLabel)
        tile.label = label
        tile.contentDescription = label
        tile.icon = Icon.createWithResource(this, SosController.normalIcon(this))
        tile.state = if (state.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    // WO-22: API 34 rejects the old Intent overload for this target SDK.
    // WO-22: this lint detector ignores SDK guards; only the guarded API 24–33 call needs the exception.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openMain() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, MAIN_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val MAIN_REQUEST = 2204 // WO-22: an explicit launcher token carries no toggle extras.
    }
}
