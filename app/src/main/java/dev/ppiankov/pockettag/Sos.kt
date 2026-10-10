package dev.ppiankov.pockettag

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService

// WO-22: presentation mode never changes the emergency bytes a helper receives.
internal enum class SosMode { OPEN, COVERT }

// WO-22: a deliberate deletion is distinct from an unreadable or missing stored entry.
internal enum class SosOffReason { ITEM_DELETED }

// WO-22: SOS preferences are separate from the normal active item and Serve tag switch.
internal data class SosState(
    val enabled: Boolean = false, // WO-22: overrides Serve tag only while explicitly enabled.
    val itemId: String? = null, // WO-22: only this existing emergency card may be served during SOS.
    val mode: SosMode = SosMode.OPEN, // WO-22: visible notification is the default presentation.
    val offReason: SosOffReason? = null, // WO-22: deletion leaves an explanation after turning SOS off.
    val notificationAsked: Boolean = false, // WO-22: denial or dismissal must not cause repeat prompts.
    val permissionPending: Boolean = false, // WO-22: only an Open activation may queue a runtime request.
)

// WO-22: this is the single selection rule for HCE, chip goals and visible serving identity.
internal fun servedItem(state: ItemState, enabled: Boolean, sos: SosState): TagItem? = when {
    !state.readable -> null
    sos.enabled -> state.items.firstOrNull { it.id == sos.itemId } as? TagItem.IfFound
    enabled -> state.activeItem
    else -> null
}

// WO-22: encoding failure serves nothing, retaining the existing whole-file size gate.
internal fun servedNdefFile(item: TagItem?): ByteArray? = try {
    item?.let { NdefMessage.ndefFile(it.ndefMessage()) }
} catch (_: Exception) {
    null
}

// WO-22: both mode transitions and activation share the first-Open permission policy.
internal fun sosPermissionRequest(sos: SosState, runtimePermission: Boolean): Boolean =
    runtimePermission && sos.enabled && sos.mode == SosMode.OPEN &&
        sos.permissionPending && !sos.notificationAsked

// WO-22: outside-app presentation remains independent of whether NFC can currently answer.
internal data class SosPresentation(
    val notification: Boolean, // WO-22: Covert never posts, including after a mode change.
    val tileLabel: Int, // WO-22: Covert uses the ordinary app name, including accessibility text.
)

internal fun sosPresentation(sos: SosState, notificationsAllowed: Boolean) = SosPresentation(
    notification = sos.enabled && sos.mode == SosMode.OPEN && notificationsAllowed,
    tileLabel = if (sos.mode == SosMode.OPEN) R.string.sos_tile_label else R.string.app_name,
)

// WO-22: only measured models get a locked-screen claim; Samsung still requires unlocking.
internal enum class SosLockSupport { P40_SCREEN_ON, S25_UNLOCK_REQUIRED, UNVERIFIED }

internal fun sosLockSupport(model: String): SosLockSupport = when (model) {
    "ELS-N39" -> SosLockSupport.P40_SCREEN_ON
    "SM-S931B" -> SosLockSupport.S25_UNLOCK_REQUIRED
    else -> SosLockSupport.UNVERIFIED
}

// WO-22: the owner can replace this text; mode never substitutes different reader content.
internal fun defaultSosMessage(name: String, phone: String): String =
    "Please help me quietly. Call $name: $phone or local emergency services. Please do not draw attention."

// WO-22: use the existing pockettag settings file and expose atomic editor composition to ItemStore.
internal class SosPrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(TagPrefs.prefs(context))

    fun load(): SosState = try {
        val values = prefs.all
        SosState(
            enabled = values[KEY_ENABLED] as? Boolean ?: false,
            itemId = values[KEY_ITEM_ID] as? String,
            mode = SosMode.entries.firstOrNull { it.name == values[KEY_MODE] } ?: SosMode.OPEN,
            offReason = SosOffReason.entries.firstOrNull { it.name == values[KEY_OFF_REASON] },
            notificationAsked = values[KEY_NOTIFICATION_ASKED] as? Boolean ?: false,
            permissionPending = values[KEY_PERMISSION_PENDING] as? Boolean ?: false,
        )
    } catch (_: Exception) {
        SosState()
    }

    fun setEnabled(enabled: Boolean, runtimePermission: Boolean) {
        val state = load().copy(enabled = enabled, offReason = null)
        write(prefs.edit(), permissionState(state, runtimePermission)).apply()
    }

    fun setMode(mode: SosMode, runtimePermission: Boolean) {
        write(prefs.edit(), permissionState(load().copy(mode = mode), runtimePermission)).apply()
    }

    // WO-22: configuration shares the item editor so a new target cannot be observed half-saved.
    fun configured(itemId: String, mode: SosMode, runtimePermission: Boolean): SosState =
        permissionState(load().copy(itemId = itemId, mode = mode, offReason = null), runtimePermission)

    // WO-22: store the asked flag before Android opens its dialog, including a dismissed request.
    fun markPermissionAsked(): Boolean {
        val state = load()
        if (!sosPermissionRequest(state, runtimePermission = true)) return false
        write(prefs.edit(), state.copy(notificationAsked = true, permissionPending = false)).apply()
        return true
    }

    fun write(editor: SharedPreferences.Editor, state: SosState): SharedPreferences.Editor = editor
        .putBoolean(KEY_ENABLED, state.enabled).putString(KEY_ITEM_ID, state.itemId)
        .putString(KEY_MODE, state.mode.name).putString(KEY_OFF_REASON, state.offReason?.name)
        .putBoolean(KEY_NOTIFICATION_ASKED, state.notificationAsked)
        .putBoolean(KEY_PERMISSION_PENDING, state.permissionPending)

    // WO-22: restored state alone cannot request permission; a persisted activation queues it.
    private fun permissionState(state: SosState, runtimePermission: Boolean): SosState = state.copy(
        permissionPending = runtimePermission && state.enabled && state.mode == SosMode.OPEN &&
            !state.notificationAsked,
    )

    companion object {
        const val KEY_ENABLED = "sos_enabled" // WO-22: independent emergency override.
        const val KEY_ITEM_ID = "sos_item_id" // WO-22: stable emergency-card identity.
        const val KEY_MODE = "sos_mode" // WO-22: Open or Covert presentation.
        const val KEY_OFF_REASON = "sos_off_reason" // WO-22: deliberate deletion explanation.
        const val KEY_NOTIFICATION_ASKED = "sos_notification_asked" // WO-22: one runtime permission request.
        const val KEY_PERMISSION_PENDING = "sos_permission_pending" // WO-22: activation handoff to the activity.
        val KEYS = setOf(KEY_ENABLED, KEY_ITEM_ID, KEY_MODE, KEY_OFF_REASON,
            KEY_NOTIFICATION_ASKED, KEY_PERMISSION_PENDING) // WO-22: both screens and the tile observe SOS changes.
    }
}

// WO-22: all activation paths persist before recomputing the chip goal and external presentation.
internal object SosController {
    private const val CHANNEL_ID = "sos_open" // WO-22: only Open mode owns a notification channel.
    private const val NOTIFICATION_ID = 22 // WO-22: one ongoing notification is replaced or cancelled.
    private const val OFF_REQUEST = 2201 // WO-22: Off has an explicit immutable broadcast token.
    private const val MAIN_REQUEST = 2202 // WO-22: opening the launcher grants no toggle authority.
    const val PERMISSION_REQUEST = 2203 // WO-22: identify the first Open notification permission result.

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val prefs = SosPrefs(context)
        if (enabled && servedItem(ItemStore(context).load(), false,
                prefs.load().copy(enabled = true)) == null) return false
        prefs.setEnabled(enabled, Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        ChipSync.sosChanged(context)
        refresh(context)
        return true
    }

    fun setMode(context: Context, mode: SosMode) {
        SosPrefs(context).setMode(mode, Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        refresh(context)
    }

    // WO-22: channel settings and permission denial affect visibility, never emergency serving.
    fun notificationsAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun refresh(context: Context) {
        val state = SosPrefs(context).load()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null) {
            if (state.enabled && state.mode == SosMode.OPEN) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                        context.getString(R.string.sos_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                        setSound(null, null)
                        enableVibration(false)
                    })
                }
                if (sosPresentation(state, notificationsAvailable(context)).notification) {
                    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Notification.Builder(context, CHANNEL_ID)
                    } else Notification.Builder(context)
                    val off = PendingIntent.getBroadcast(context, OFF_REQUEST,
                        Intent(context, SosOffReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val main = PendingIntent.getActivity(context, MAIN_REQUEST,
                        Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val notification = builder.setSmallIcon(normalIcon(context))
                        .setContentTitle(context.getString(R.string.sos_notification_title))
                        .setContentIntent(main).setOngoing(true).setOnlyAlertOnce(true)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .addAction(Notification.Action.Builder(null, context.getString(R.string.sos_off), off).build())
                        .build()
                    try {
                        manager.notify(NOTIFICATION_ID, notification)
                    } catch (_: SecurityException) {
                        // WO-22: permission may be revoked after the check; SOS must keep serving.
                        manager.cancel(NOTIFICATION_ID)
                    }
                } else manager.cancel(NOTIFICATION_ID)
            } else manager.cancel(NOTIFICATION_ID)
        }
        // WO-22: ACTIVE_TILE metadata makes this request refresh a previously bound tile.
        TileService.requestListeningState(context, ComponentName(context, SosTileService::class.java))
    }

    // WO-22: the existing app has no custom icon, so use Android's ordinary app icon fallback.
    fun normalIcon(context: Context): Int = context.applicationInfo.icon.takeIf { it != 0 }
        ?: android.R.drawable.sym_def_app_icon
}

// WO-22: both foreground screens state the override and the measured locked-screen limits plainly.
internal fun sosStatus(context: Context, sos: SosState, enabled: Boolean, chipMode: Boolean): String? {
    if (!sos.enabled) return if (sos.offReason == SosOffReason.ITEM_DELETED) {
        context.getString(R.string.sos_item_deleted)
    } else null
    val lock = if (chipMode) null else context.getString(when (sosLockSupport(Build.MODEL)) {
        SosLockSupport.P40_SCREEN_ON -> R.string.sos_lock_p40
        SosLockSupport.S25_UNLOCK_REQUIRED -> R.string.sos_lock_s25
        SosLockSupport.UNVERIFIED -> R.string.sos_lock_unverified
    })
    return listOfNotNull(context.getString(R.string.sos_on),
        if (!enabled) context.getString(R.string.sos_overrides_off) else null,
        if (sos.mode == SosMode.OPEN && !SosController.notificationsAvailable(context)) {
            context.getString(R.string.sos_notification_unavailable)
        } else null, lock).joinToString("\n")
}
