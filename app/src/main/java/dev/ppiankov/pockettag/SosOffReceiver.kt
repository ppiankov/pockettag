package dev.ppiankov.pockettag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// WO-22: the Open notification's immutable explicit action turns SOS off without changing selection.
class SosOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SosController.setEnabled(context, false)
    }
}
