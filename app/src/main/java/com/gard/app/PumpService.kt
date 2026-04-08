package com.gard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler

class PumpService : Service() {

    private val binder = LocalBinder()
    lateinit var myPump: GarDPump
    private var bluetoothHandler: TandemBluetoothHandler? = null
    private val pollingHandler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null

    companion object {
        const val CHANNEL_ID = "PumpServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    inner class LocalBinder : Binder() {
        fun getService(): PumpService = this@PumpService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Pump Service Running", "Maintaining connection to pump...")
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun initPump(activity: MainActivity) {
        if (!::myPump.isInitialized) {
            myPump = GarDPump(applicationContext, activity)
        }
    }

    fun startBluetooth(pairingCode: String) {
        if (pairingCode.isNotEmpty()) {
            myPump.pairingCode = pairingCode
        }
        try {
            bluetoothHandler = TandemBluetoothHandler.getInstance(applicationContext, myPump)
            bluetoothHandler?.startScan()
        } catch (e: Exception) {
            myPump.activity.appendLog("Scan Error: ${e.message}")
        }
    }

    fun startStatusPolling() {
        if (pollingRunnable != null) return
        pollingRunnable = object : Runnable {
            override fun run() {
                myPump.requestRealtimeStatus()
                pollingHandler.postDelayed(this, 2 * 60 * 1000)
            }
        }
        pollingHandler.postDelayed(pollingRunnable!!, 2 * 60 * 1000)
    }

    fun stopStatusPolling() {
        pollingRunnable?.let { pollingHandler.removeCallbacks(it) }
        pollingRunnable = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Pump Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .build()
    }
}
