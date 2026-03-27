package com.gard.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler
import timber.log.Timber
import android.view.View
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.text.Editable
import android.text.TextWatcher
import androidx.biometric.BiometricPrompt
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "gard_extended_bolus"
        const val ACTION_CANCEL_EXTENDED = "com.gard.app.CANCEL_EXTENDED"
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_EXTENDED) {
                myPump.cancelExtendedBolus()
            }
        }
    }

    private val cgmReceiver = CgmBroadcastReceiver { glucose, timestamp ->
        updateCGM(glucose)
        appendLog("CGM Update via Broadcast: $glucose at $timestamp")
    }

    private lateinit var layoutConnect: View
    private lateinit var layoutMain: View
    private lateinit var tvStatus: TextView
    private lateinit var tvBattery: TextView
    private lateinit var etPairingCode: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvLog: TextView
    
    private lateinit var myPump: GarDPump
    private var bluetoothHandler: TandemBluetoothHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver, IntentFilter(ACTION_CANCEL_EXTENDED), RECEIVER_NOT_EXPORTED)
            registerReceiver(cgmReceiver, IntentFilter(CgmBroadcastReceiver.ACTION_BG_ESTIMATE), RECEIVER_EXPORTED)
        } else {
            registerReceiver(cancelReceiver, IntentFilter(ACTION_CANCEL_EXTENDED))
            registerReceiver(cgmReceiver, IntentFilter(CgmBroadcastReceiver.ACTION_BG_ESTIMATE))
        }

        layoutConnect = findViewById(R.id.layoutConnect)
        layoutMain = findViewById(R.id.layoutMain)
        
        tvStatus = findViewById(R.id.tvStatus)
        tvBattery = findViewById(R.id.tvBattery)
        etPairingCode = findViewById(R.id.etPairingCode)
        btnConnect = findViewById(R.id.btnConnect)
        tvLog = findViewById(R.id.tvLog)
        
        val btnCopyLogs: Button = findViewById(R.id.btnCopyLogs)
        val btnBolus: Button = findViewById(R.id.btnBolus)
        val etBolusUnits: EditText = findViewById(R.id.etBolusUnits)
        val etExtendedMinutes: EditText = findViewById(R.id.etExtendedMinutes)
        val etUnitsNow: EditText = findViewById(R.id.etUnitsNow)
        val layoutUnitsNow: View = findViewById(R.id.layoutUnitsNow)
        val tvUnitsNowLabel: TextView = findViewById(R.id.tvUnitsNowLabel)
        
        tvUnitsNowLabel.text = "Units Now"

        myPump = GarDPump(this, this)
        
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                appendLog(message)
            }
        })
        
        etExtendedMinutes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val mins = s.toString().toIntOrNull() ?: 0
                if (mins > 0) {
                    layoutUnitsNow.visibility = View.VISIBLE
                } else {
                    layoutUnitsNow.visibility = View.GONE
                    etUnitsNow.setText("0")
                }
            }
        })

        btnConnect.setOnClickListener {
            myPump.pairingCode = etPairingCode.text.toString().trim()
            checkPermissionsAndStart()
        }

        tvStatus.setOnLongClickListener {
            myPump.startSimulator()
            true
        }

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GarD Logs", tvLog.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs Copied to Clipboard!", Toast.LENGTH_SHORT).show()
        }
        
        btnBolus.setOnClickListener {
            val totalUnitsStr = etBolusUnits.text.toString()
            val totalUnits = totalUnitsStr.toDoubleOrNull() ?: 0.0
            
            val extMinStr = etExtendedMinutes.text.toString()
            val extMin = extMinStr.toIntOrNull() ?: 0
            
            val unitsNowStr = etUnitsNow.text.toString()
            val unitsNow = unitsNowStr.toDoubleOrNull() ?: 0.0
            
            if (totalUnits <= 0.0 || totalUnits > 10.0) {
                Toast.makeText(this, "Invalid total units!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (unitsNow > totalUnits) {
                Toast.makeText(this, "Units Now cannot exceed Total Units!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (extMin < 0 || extMin > 60) {
                Toast.makeText(this, "Extended minutes must be 0-60", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            showBiometricPrompt(totalUnits, extMin, unitsNow)
        }

        if (hasPermissions()) {
            startBluetooth()
        }
    }

    private fun hasPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            startBluetooth()
        }
    }

    private fun startBluetooth() {
        tvStatus.text = "Status: Scanning..."
        try {
            bluetoothHandler = TandemBluetoothHandler.getInstance(applicationContext, myPump)
            bluetoothHandler?.startScan()
        } catch (e: Exception) {
            tvStatus.text = "Status: Scan Error"
        }
    }

    fun appendLog(msg: String) {
        Log.i("GarDPump-UI", msg)
        runOnUiThread {
            val currentText = tvLog.text.toString()
            val newText = if (currentText == "Logs will appear here...") msg else "$currentText\n$msg"
            tvLog.text = newText
        }
    }

    private var pollingRunnable: Runnable? = null
    private val pollingHandler = Handler(Looper.getMainLooper())

    private fun startStatusPolling() {
        if (pollingRunnable != null) return
        pollingRunnable = object : Runnable {
            override fun run() {
                myPump.requestRealtimeStatus()
                pollingHandler.postDelayed(this, 2 * 60 * 1000)
            }
        }
        pollingHandler.postDelayed(pollingRunnable!!, 2 * 60 * 1000)
    }

    private fun stopStatusPolling() {
        pollingRunnable?.let { pollingHandler.removeCallbacks(it) }
        pollingRunnable = null
    }

    fun updateStatus(status: String) {
        findViewById<TextView>(R.id.tvStatus).text = "Status: $status"
        if (status == "Connected & Initialized!") {
            layoutConnect.visibility = View.GONE
            layoutMain.visibility = View.VISIBLE
            startStatusPolling()
        } else if (status.startsWith("Disconnected")) {
            layoutConnect.visibility = View.VISIBLE
            layoutMain.visibility = View.GONE
            stopStatusPolling()
        }
    }

    fun updateBattery(percent: Int) {
        findViewById<TextView>(R.id.tvBattery).text = "Battery: $percent%"
    }

    fun updateIOB(iob: Double) {
        findViewById<TextView>(R.id.tvIOB).text = String.format("IOB: %.2f U", iob)
    }

    fun updateInsulin(units: Int) {
        findViewById<TextView>(R.id.tvInsulin).text = "Insulin Remaining: $units U"
    }

    fun updateCGM(cgm: Int) {
        findViewById<TextView>(R.id.tvCGM).text = if (cgm > 0) "CGM: $cgm mg/dL" else "CGM: NA"
    }

    fun updateSessionSummary(delivered: Double, total: Double, elapsedMin: Int, totalMin: Int) {
        val tv = findViewById<TextView>(R.id.tvBolusProgress)
        val container = findViewById<View>(R.id.layoutBolusProgress)
        if (total <= 0.0 && totalMin <= 0) {
            container.visibility = View.GONE
            return
        }
        val dFmt = String.format("%.1f", delivered)
        val tFmt = String.format("%.1f", total)
        tv.text = if (totalMin > 0) "$dFmt / $tFmt U   in   $elapsedMin / $totalMin min" else "Delivered: $dFmt / $tFmt U"
        container.visibility = View.VISIBLE
    }

    private fun showBiometricPrompt(totalUnits: Double, extMin: Int, unitsNow: Double) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    showDeliveryDialog(totalUnits, extMin, unitsNow)
                    myPump.sendBolus(totalUnits, extMin, unitsNow)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        val subtitle = buildString {
            if (extMin > 0) {
                if (unitsNow > 0) append("NOW: ${String.format("%.2f", unitsNow)} U immediately\n")
                val volumeExt = totalUnits - unitsNow
                append("EXTENDED: ${String.format("%.2f", volumeExt)} U over $extMin min")
            } else {
                append("${String.format("%.2f", totalUnits)} U immediate bolus")
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirm Bolus — $totalUnits U total")
            .setSubtitle(subtitle)
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(cancelReceiver)
        unregisterReceiver(cgmReceiver)
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val name = "Extended Bolus"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updateExtendedNotification(total: Double, delivered: Double, remainingMins: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val cancelIntent = Intent(ACTION_CANCEL_EXTENDED)
        val cancelPendingIntent = PendingIntent.getBroadcast(this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentTitle("Extended Bolus Active")
            .setContentText("Delivered ${String.format("%.2f", delivered)} / ${String.format("%.2f", total)} U ($remainingMins min left)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCEL EXTENDED", cancelPendingIntent)
            
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancelExtendedNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private var deliveryDialog: AlertDialog? = null
    private val deliveryTimeoutHandler = Handler(Looper.getMainLooper())
    private val deliveryTimeoutRunnable = Runnable { dismissDeliveryDialog(true) }
    private var deliveryStartTime = 0L

    fun showDeliveryDialog(totalUnits: Double, extMin: Int, unitsNow: Double) {
        if (deliveryDialog != null && deliveryDialog!!.isShowing) return
        deliveryStartTime = System.currentTimeMillis()
        onBolusDeliveryStarted()

        val builder = AlertDialog.Builder(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 80, 80, 80)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            gravity = Gravity.CENTER
        }
        
        val tv = TextView(this).apply {
            text = "Delivering ${String.format("%.2f", totalUnits)} U total...\nPlease Wait."
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        layout.addView(tv)
        builder.setView(layout)
        builder.setCancelable(false)
        
        deliveryDialog = builder.create()
        deliveryDialog?.show()
        
        deliveryTimeoutHandler.removeCallbacks(deliveryTimeoutRunnable)
        deliveryTimeoutHandler.postDelayed(deliveryTimeoutRunnable, 60000)
    }

    fun dismissDeliveryDialog(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - deliveryStartTime < 5000) return
        deliveryDialog?.dismiss()
        deliveryDialog = null
        deliveryTimeoutHandler.removeCallbacks(deliveryTimeoutRunnable)
        onBolusDeliveryEnded()
    }

    fun onBolusDeliveryStarted() {
        findViewById<Button>(R.id.btnBolus).apply { text = "DELIVERING..."; isEnabled = false }
    }

    fun onBolusDeliveryEnded() {
        findViewById<Button>(R.id.btnBolus).apply { text = "BOLUS"; isEnabled = true }
    }
}
