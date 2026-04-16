package com.gard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

import timber.log.Timber

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("Alarm fired: ${intent.action}")
        val serviceIntent = Intent(context, PumpService::class.java)
        serviceIntent.action = "POLL_ACTION"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
