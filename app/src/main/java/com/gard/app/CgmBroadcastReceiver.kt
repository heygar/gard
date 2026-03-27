package com.gard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CgmBroadcastReceiver(private val onGlucoseReceived: (Int, Long) -> Unit) : BroadcastReceiver() {
    companion object {
        const val ACTION_BG_ESTIMATE = "com.eveningoutpost.dexdrip.BgEstimate"
        const val EXTRA_GLUCOSE = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
        const val EXTRA_TIMESTAMP = "com.eveningoutpost.dexdrip.Extras.Time"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_BG_ESTIMATE) {
            val glucose = intent.getDoubleExtra(EXTRA_GLUCOSE, 0.0).toInt()
            val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, 0L)
            
            Log.i("CgmReceiver", "Received Glucose: $glucose at $timestamp")
            if (glucose > 0) {
                onGlucoseReceived(glucose, timestamp)
            }
        }
    }
}
