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
import android.os.PowerManager
import android.app.AlarmManager
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
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var alarmManager: AlarmManager

    // State for notification
    var lastStatus: String = "Disconnected"
        private set
    var lastUpdateMillis: Long = 0L
        private set
    var currentGlucose: Int = 0
        private set
    var currentIOB: Double = 0.0
        private set
    var currentInsulin: Int = 0
        private set
    var currentBattery: Int = 0
        private set
    private var isExtendedActive: Boolean = false
    private var extDelivered: Double = 0.0
    private var extTotal: Double = 0.0
    private var extRemaining: Int = 0
    private var isForeground = false
    private var reconnectCount = 0

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

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GarD:PumpServiceWakeLock")
        // Acquire wake lock and keep it active
        wakeLock?.acquire(10*60*1000) // 10 minutes, will be refreshed
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

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
        isForeground = true
    }

    // Add a method to refresh wake lock periodically
    private fun refreshWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
            it.acquire(10*60*1000) // 10 minutes
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "POLL_ACTION") {
            doPoll()
        }
        // Keep service running
        return START_STICKY
    }

    // Add a method to refresh the foreground service to prevent being killed
    private fun refreshForegroundService() {
        if (isForeground) {
            // Update the notification to keep the service alive
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
        scheduleNextPoll()
        // No longer using Handler loop to avoid fighting with AlarmManager
        pollingRunnable = object : Runnable { override fun run() {} }
    }

    private fun doPoll() {
        // Ensure wake lock is held during polling
        refreshWakeLock()
        
        // Refresh foreground service to prevent being killed
        refreshForegroundService()
        
        if (myPump.connectedPeripheral == null) {
            appendLog("Watchdog: Pump currently disconnected. Attempting reconnect...")
            startBluetooth("")
        } else {
            appendLog("Watchdog: Requesting status update...")
            myPump.requestRealtimeStatus(force = true)
        }
        scheduleNextPoll()
    }

    private fun scheduleNextPoll() {
        val intent = Intent(this, AlarmReceiver::class.java)
        intent.action = "POLL_ACTION"
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val triggerAtMillis = System.currentTimeMillis() + 2 * 60 * 1000
        
        // Ensure wake lock is held when scheduling alarm
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(10*60*1000)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            if (alarmManager.canScheduleExactAlarms()) {
                // Use setAlarmClock for maximum priority on Samsung devices
                val info = AlarmManager.AlarmClockInfo(triggerAtMillis, pendingIntent)
                alarmManager.setAlarmClock(info, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
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
            append(String.format(Locale.getDefault(), "IOB: %.2f U | CGM: %d | Bat: %d%%", currentIOB, currentGlucose, currentBattery))
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
            .setShowWhen(true)
            .setWhen(if (lastUpdateMillis > 0) lastUpdateMillis else System.currentTimeMillis())

        if (isExtendedActive) {
            val cancelIntent = Intent(MainActivity.ACTION_CANCEL_EXTENDED)
            val cancelPendingIntent = PendingIntent.getBroadcast(this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCEL EXTENDED", cancelPendingIntent)
        }

        return builder.build()
    }

    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationRunnable = Runnable { updateNotificationImmediate() }

    private fun updateNotification() {
        notificationHandler.removeCallbacks(notificationRunnable)
        notificationHandler.postDelayed(notificationRunnable, 500) // Wait 500ms for all pump data to arrive
    }

    private fun updateNotificationImmediate() {
        if (isForeground) {
            val notification = createNotification()
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } else {
            startServiceForeground()
        }
    }

    // Add a method to ensure notification is always updated
    private fun ensureNotificationUpdated() {
        updateNotificationImmediate()
    }

    // PumpUpdateListener implementation
    override fun appendLog(msg: String) {
        callback?.appendLog(msg)
    }

    override fun updateStatus(status: String) {
        lastStatus = status
        if (status == "Connected & Initialized!") {
            reconnectCount = 0
            startStatusPolling()
        } else if (status == "Disconnected") {
            // Kick off an auto-reconnect attempt with exponential backoff!
            reconnectCount++
            val delay = (5000L * reconnectCount).coerceAtMost(60000L)
            appendLog("Auto-reconnect attempt $reconnectCount in ${delay/1000}s...")
            
            // Still using Handler for immediate local reconnect, but AlarmManager will also trigger it
            pollingHandler.postDelayed({
                if (lastStatus == "Disconnected") {
                    startBluetooth("")
                }
            }, delay)
        }
        updateNotification()
        callback?.updateStatus(status)
    }

    override fun onDestroy() {
        stopStatusPolling()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        // Cancel any pending alarms
        val intent = Intent(this, AlarmReceiver::class.java)
        intent.action = "POLL_ACTION"
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntent)
        callback = null
        super.onDestroy()
    }

    override fun updateBattery(percent: Int) {
        lastUpdateMillis = System.currentTimeMillis()
        currentBattery = percent
        updateNotification()
        callback?.updateBattery(percent)
    }

    override fun updateIOB(iob: Double) {
        lastUpdateMillis = System.currentTimeMillis()
        currentIOB = iob
        updateNotification()
        callback?.updateIOB(iob)
    }

    override fun updateInsulin(units: Int) {
        lastUpdateMillis = System.currentTimeMillis()
        currentInsulin = units
        updateNotification()
        callback?.updateInsulin(units)
    }

    override fun updateCGM(glucose: Int, trend: String, timestamp: Long) {
        lastUpdateMillis = System.currentTimeMillis()
        currentGlucose = glucose
        updateNotification()
        callback?.updateCGM(glucose, trend, timestamp)
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