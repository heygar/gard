package com.gard.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler
import java.util.Locale

class PumpService : Service(), PumpUpdateListener {

    private val binder = LocalBinder()
    lateinit var myPump: GarDPump
    var callback: PumpUpdateListener? = null

    private var bluetoothHandler: TandemBluetoothHandler? = null
    private val pollingHandler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null

    // State for notification
    private var lastStatus: String = "Disconnected"
    private var currentGlucose: Int = 0
    private var currentIOB: Double = 0.0
    private var currentInsulin: Int = 0
    private var currentBattery: Int = 0
    private var isExtendedActive: Boolean = false
    private var extDelivered: Double = 0.0
    private var extTotal: Double = 0.0
    private var extRemaining: Int = 0

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
        myPump = GarDPump(applicationContext)
        myPump.callback = this
        // Start foreground service immediately
        startServiceForeground()
    }

    private fun startServiceForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep service running
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun startBluetooth(pairingCode: String) {
        appendLog("Starting Bluetooth scan...")
        if (pairingCode.isNotEmpty()) {
            myPump.pairingCode = pairingCode
        }

        try {
            // Check if we have the required permissions first
            if (!hasRequiredPermissions()) {
                appendLog("Missing required permissions for Bluetooth")
                callback?.updateStatus("Missing Permissions")
                return
            }

            // Start the actual connection process
            bluetoothHandler = TandemBluetoothHandler.getInstance(applicationContext, myPump)
            bluetoothHandler?.startScan()

        } catch (e: Exception) {
            appendLog("Scan Error: ${e.message}")
            callback?.updateStatus("Scan Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun startStatusPolling() {
        if (pollingRunnable != null) return
        appendLog("Starting status polling (2m intervals)")
        pollingRunnable = object : Runnable {
            override fun run() {
                if (myPump.connectedPeripheral == null) {
                    // Watchdog caught a dead connection. Force a scan.
                    appendLog("Watchdog: Pump disconnected. Restarting scan...")
                    startBluetooth("")
                } else {
                    // Normal behavior
                    myPump.requestRealtimeStatus()
                }
                pollingHandler.postDelayed(this, 2 * 60 * 1000)
            }
        }
        pollingHandler.post(pollingRunnable!!)
    }

    fun stopStatusPolling() {
        pollingRunnable?.let { pollingHandler.removeCallbacks(it) }
        pollingRunnable = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Pump Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val title = if (lastStatus == "Connected & Initialized!") {
            "Pump Connected"
        } else {
            lastStatus
        }

        val content = buildString {
            append(String.format(Locale.getDefault(), "IOB: %.2f U | Pump: %d U | Bat: %d%%", currentIOB, currentInsulin, currentBattery))
            if (isExtendedActive) {
                append(String.format(Locale.getDefault(), "\nExt: %.2f / %.2f U (%d min left)", extDelivered, extTotal, extRemaining))
            }
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentText(content.split("\n")[0])
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)

        if (isExtendedActive) {
            val cancelIntent = Intent(MainActivity.ACTION_CANCEL_EXTENDED)
            val cancelPendingIntent = PendingIntent.getBroadcast(this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCEL EXTENDED", cancelPendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification() {
        startServiceForeground()
    }

    // PumpUpdateListener implementation
    override fun appendLog(msg: String) {
        callback?.appendLog(msg)
    }

    override fun updateStatus(status: String) {
        lastStatus = status
        if (status == "Connected & Initialized!") {
            startStatusPolling()
        } else if (status == "Disconnected") {
            // Kick off an auto-reconnect attempt!
            // We wait 5 seconds to let the Android BLE stack clear out old dead connections.
            pollingHandler.postDelayed({
                appendLog("Attempting auto-reconnect...")
                startBluetooth("")
            }, 5000)
        }
        updateNotification()
        callback?.updateStatus(status)
    }

    override fun updateBattery(percent: Int) {
        currentBattery = percent
        updateNotification()
        callback?.updateBattery(percent)
    }

    override fun updateIOB(iob: Double) {
        currentIOB = iob
        updateNotification()
        callback?.updateIOB(iob)
    }

    override fun updateInsulin(units: Int) {
        currentInsulin = units
        updateNotification()
        callback?.updateInsulin(units)
    }

    override fun updateCGM(glucose: Int, trend: String) {
        currentGlucose = glucose
        updateNotification()
        callback?.updateCGM(glucose, trend)
    }

    override fun updateSessionSummaryMulti(lines: List<String>) {
        callback?.updateSessionSummaryMulti(lines)
    }

    override fun updateExtendedNotification(total: Double, delivered: Double, remainingMins: Int) {
        isExtendedActive = true
        extTotal = total
        extDelivered = delivered
        extRemaining = remainingMins
        updateNotification()
        callback?.updateExtendedNotification(total, delivered, remainingMins)
    }

    override fun cancelExtendedNotification() {
        isExtendedActive = false
        updateNotification()
        callback?.cancelExtendedNotification()
    }

    override fun dismissDeliveryDialog(force: Boolean) {
        callback?.dismissDeliveryDialog(force)
    }

    override fun getPendingContributions(): Map<Int, Double> {
        return callback?.getPendingContributions() ?: emptyMap()
    }

    override fun setPendingContributions(contribs: Map<Int, Double>) {
        callback?.setPendingContributions(contribs)
    }

    override fun markBolusReady() {
        callback?.markBolusReady()
    }

    override fun uploadGlucoseMulti(entries: List<NightscoutClient.GlucoseEntry>) {
        callback?.uploadGlucoseMulti(entries)
    }
}