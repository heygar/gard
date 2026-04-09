package com.gard.app

import android.Manifest
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Locale
import android.view.MotionEvent

class MainActivity : AppCompatActivity(), PumpUpdateListener {


    companion object {
        const val ACTION_CANCEL_EXTENDED = "com.gard.app.CANCEL_EXTENDED"
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_EXTENDED) {
                pumpService?.myPump?.cancelExtendedBolus()
            }
        }
    }

    private val cgmReceiver = CgmBroadcastReceiver { glucose, timestamp ->
        appendLog("Ignored Broadcast CGM: $glucose at $timestamp")
    }

    private lateinit var layoutConnect: View
    private lateinit var layoutMain: View
    private lateinit var tvStatus: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvIOB: TextView
    private lateinit var tvInsulin: TextView
    private lateinit var etPairingCode: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvLog: TextView
    private lateinit var viewConnDot: View
    private lateinit var cbUploadToCloud: CheckBox
    private lateinit var tvCGM: TextView
    
    private var pumpService: PumpService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as PumpService.LocalBinder
            pumpService = binder.getService()
            isBound = true
            pumpService?.callback = this@MainActivity
            
            // Re-sync UI with current service state
            pumpService?.myPump?.connectedPeripheral?.let {
                updateStatus("Connected & Initialized!")
            }

            // Restore automatic connection if permissions are granted
            if (hasPermissions()) {
                pumpService?.startBluetooth("")
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            pumpService = null
        }
    }
    
    val nsClient = NightscoutClient("https://nightscout.heygar.com/", "YourSecret12!") { msg ->
        appendLog("NS: $msg")
    }

    private var isPhysicallyDelivering = false
    private var activeExtendedCount = 0
    private var pendingContributions: Map<Int, Double> = emptyMap()
    
    private var lastGlucose = 0
    private var lastGlucoseTimestamp = 0L
    private var testGlucoseValue = 87

    private var statusTouchDownTime = 0L
    private val statusLongPressHandler = Handler(Looper.getMainLooper())
    private val statusLongPressRunnable = Runnable {
        appendLog("Simulator trigger: starting...")
        pumpService?.myPump?.startSimulator()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        layoutConnect = findViewById(R.id.layoutConnect)
        layoutMain = findViewById(R.id.layoutMain)
        
        tvStatus = findViewById(R.id.tvStatus)
        tvBattery = findViewById(R.id.tvBattery)
        tvIOB = findViewById(R.id.tvIOB)
        tvInsulin = findViewById(R.id.tvInsulin)
        etPairingCode = findViewById(R.id.etPairingCode)
        btnConnect = findViewById(R.id.btnConnect)
        tvLog = findViewById(R.id.tvLog)
        viewConnDot = findViewById(R.id.viewConnDot)
        cbUploadToCloud = findViewById(R.id.cbUploadToCloud)
        tvCGM = findViewById(R.id.tvCGM)

        // Bind the foreground service (start it if not running)
        val intent = Intent(this, PumpService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, BIND_AUTO_CREATE)

        val filterCancel = IntentFilter(ACTION_CANCEL_EXTENDED)
        val filterCgm = IntentFilter(CgmBroadcastReceiver.ACTION_BG_ESTIMATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver, filterCancel, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(cgmReceiver, filterCgm, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(cancelReceiver, filterCancel)
            registerReceiver(cgmReceiver, filterCgm)
        }
        
        val btnCopyLogs: Button = findViewById(R.id.btnCopyLogs)
        val btnBolus: Button = findViewById(R.id.btnBolus)
        val etBolusUnits: EditText = findViewById(R.id.etBolusUnits)
        val etExtendedMinutes: EditText = findViewById(R.id.etExtendedMinutes)
        val etUnitsNow: EditText = findViewById(R.id.etUnitsNow)
        val layoutUnitsNow: View = findViewById(R.id.layoutUnitsNow)
        val tvUnitsNowLabel: TextView = findViewById(R.id.tvUnitsNowLabel)
        
        tvUnitsNowLabel.text = "Units Now"

        cbUploadToCloud.setOnCheckedChangeListener { _, isChecked ->
            appendLog("Cloud Sync: ${if (isChecked) "ON" else "OFF"}")
            if (isChecked && lastGlucose > 0) {
                nsClient.uploadGlucose(lastGlucose, lastGlucoseTimestamp)
            }
        }

        tvCGM.setOnLongClickListener {
            if (cbUploadToCloud.isChecked) {
                val currentTest = testGlucoseValue
                appendLog("TEST SYNC: Sending $currentTest to Nightscout...")
                nsClient.uploadGlucose(currentTest, System.currentTimeMillis())
                tvCGM.text = String.format(Locale.getDefault(), "TEST: %d", currentTest)
                testGlucoseValue++
                true
            } else {
                Toast.makeText(this, "Enable Cloud Sync to test!", Toast.LENGTH_SHORT).show()
                false
            }
        }

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
                }
            }
        })

        btnConnect.setOnClickListener {
            val code = etPairingCode.text.toString().trim()
            if (code.isNotEmpty()) {
                // Only clear the secret if a NEW code is provided
                com.jwoglom.pumpx2.pump.PumpState.setJpakeDerivedSecret(this, "")
            }
            pumpService?.startBluetooth(code)
        }

        tvStatus.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    statusTouchDownTime = System.currentTimeMillis()
                    statusLongPressHandler.postDelayed(statusLongPressRunnable, 3000)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    statusLongPressHandler.removeCallbacks(statusLongPressRunnable)
                    if (event.action == MotionEvent.ACTION_UP) {
                        v.performClick()
                    }
                }
            }
            true
        }

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
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
            if (extMin !in 0..60) {
                Toast.makeText(this, "Extended minutes must be 0-60", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            showBiometricPrompt(totalUnits, extMin, unitsNow)
        }
    }

    private fun hasPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            pumpService?.startBluetooth("")
        }
    }

    private val logBuffer = java.util.LinkedList<String>()

    override fun appendLog(msg: String) {
        runOnUiThread {
            if (!::tvLog.isInitialized) return@runOnUiThread

            logBuffer.add(msg)
            if (logBuffer.size > 100) {
                logBuffer.removeFirst()
            }

            tvLog.text = logBuffer.joinToString("\n")
        }
    }

    override fun updateStatus(status: String) {
        runOnUiThread {
            if (!::tvStatus.isInitialized) return@runOnUiThread
            tvStatus.text = String.format(Locale.getDefault(), "Status: %s", status)
            if (status == "Connected & Initialized!") {
                if (::viewConnDot.isInitialized) viewConnDot.setBackgroundColor(Color.GREEN)
                if (::layoutConnect.isInitialized) layoutConnect.visibility = View.GONE
                if (::layoutMain.isInitialized) layoutMain.visibility = View.VISIBLE
            } else if (status.startsWith("Disconnected")) {
                if (::viewConnDot.isInitialized) viewConnDot.setBackgroundColor(Color.RED)
            } else if (status == "Scanning...") {
                if (::viewConnDot.isInitialized) viewConnDot.setBackgroundColor(Color.YELLOW)
            }
        }
    }

    override fun updateBattery(percent: Int) {
        runOnUiThread { 
            if (::tvBattery.isInitialized) {
                tvBattery.text = String.format(Locale.getDefault(), "Battery: %d%%", percent)
            }
        }
    }

    override fun updateIOB(iob: Double) {
        runOnUiThread { 
            if (::tvIOB.isInitialized) {
                tvIOB.text = String.format(Locale.getDefault(), "IOB: %.2f U", iob)
            }
        }
    }

    override fun updateInsulin(units: Int) {
        runOnUiThread { 
            if (::tvInsulin.isInitialized) {
                tvInsulin.text = String.format(Locale.getDefault(), "Insulin Remaining: %d U", units)
            }
        }
    }

    override fun updateCGM(glucose: Int, trend: String) {
        runOnUiThread {
            if (::tvCGM.isInitialized && glucose > 0) {
                tvCGM.text = String.format(Locale.getDefault(), "CGM: %d mg/dL", glucose)
                lastGlucose = glucose
                lastGlucoseTimestamp = System.currentTimeMillis()
                if (cbUploadToCloud.isChecked) {
                    nsClient.uploadGlucose(glucose, lastGlucoseTimestamp, trend)
                }
            }
        }
    }

    override fun uploadGlucoseMulti(entries: List<NightscoutClient.GlucoseEntry>) {
        if (cbUploadToCloud.isChecked) {
            nsClient.uploadGlucoseMulti(entries)
        }
    }

    override fun setPendingContributions(contribs: Map<Int, Double>) {
        this.pendingContributions = contribs
    }

    override fun getPendingContributions(): Map<Int, Double> {
        return this.pendingContributions
    }

    override fun updateSessionSummaryMulti(lines: List<String>) {
        runOnUiThread {
            val tv = findViewById<TextView>(R.id.tvBolusProgress)
            val container = findViewById<View>(R.id.layoutBolusProgress)
            activeExtendedCount = lines.size
            if (lines.isEmpty()) {
                if (container != null) container.visibility = View.GONE
                refreshBolusButtonState()
                return@runOnUiThread
            }
            if (tv != null) tv.text = lines.joinToString("\n")
            if (container != null) container.visibility = View.VISIBLE
            refreshBolusButtonState()
        }
    }

    override fun markBolusReady() {
        runOnUiThread {
            val tv = findViewById<TextView>(R.id.tvBolusProgress)
            if (tv != null) {
                tv.text = "READY"
                Handler(Looper.getMainLooper()).postDelayed({
                    if (tv.text == "READY") {
                        findViewById<View>(R.id.layoutBolusProgress)?.visibility = View.GONE
                    }
                }, 5000)
            }
        }
    }

    private fun showBiometricPrompt(totalUnits: Double, extMin: Int, unitsNow: Double) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    showDeliveryDialog(totalUnits, extMin, unitsNow)
                    pumpService?.myPump?.sendBolus(totalUnits, extMin, unitsNow)
                }
            })

        val subtitle = buildString {
            if (extMin > 0) {
                if (unitsNow > 0) append(String.format(Locale.getDefault(), "NOW: %.2f U immediately\n", unitsNow))
                append(String.format(Locale.getDefault(), "EXTENDED: %.2f U over %d min", totalUnits - unitsNow, extMin))
            } else {
                append(String.format(Locale.getDefault(), "%.2f U immediate bolus", totalUnits))
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(String.format(Locale.getDefault(), "Confirm Bolus — %.2f U total", totalUnits))
            .setSubtitle(subtitle)
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        try {
            unregisterReceiver(cancelReceiver)
            unregisterReceiver(cgmReceiver)
        } catch (e: Exception) {}
    }

    override fun updateExtendedNotification(total: Double, delivered: Double, remainingMins: Int) {
        // Handled in Service
    }

    override fun cancelExtendedNotification() {
        // Handled in Service
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
            text = String.format(Locale.getDefault(), "Delivering %.2f U total...\nPlease Wait.", totalUnits)
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

    override fun dismissDeliveryDialog(force: Boolean) {
        if (!force && System.currentTimeMillis() - deliveryStartTime < 5000) return
        runOnUiThread {
            deliveryDialog?.dismiss()
            deliveryDialog = null
            deliveryTimeoutHandler.removeCallbacks(deliveryTimeoutRunnable)
            onBolusDeliveryEnded()
        }
    }

    fun onBolusDeliveryStarted() {
        isPhysicallyDelivering = true
        refreshBolusButtonState()
        runOnUiThread {
            findViewById<EditText>(R.id.etBolusUnits)?.isEnabled = false
            findViewById<EditText>(R.id.etExtendedMinutes)?.isEnabled = false
            findViewById<EditText>(R.id.etUnitsNow)?.isEnabled = false
        }
    }

    fun onBolusDeliveryEnded() {
        isPhysicallyDelivering = false
        refreshBolusButtonState()
        runOnUiThread {
            findViewById<EditText>(R.id.etBolusUnits)?.apply { isEnabled = true; setText("") }
            findViewById<EditText>(R.id.etExtendedMinutes)?.apply { isEnabled = true; setText("") }
            findViewById<EditText>(R.id.etUnitsNow)?.apply { isEnabled = true; setText("") }
        }
    }

    private fun refreshBolusButtonState() {
        runOnUiThread {
            val btnBolus = findViewById<Button>(R.id.btnBolus)
            if (btnBolus != null) {
                if (isPhysicallyDelivering) {
                    btnBolus.text = "DELIVERING..."
                    btnBolus.isEnabled = false
                } else if (activeExtendedCount >= 3) {
                    btnBolus.text = "MAX REACHED"
                    btnBolus.isEnabled = false
                } else {
                    btnBolus.text = "BOLUS"
                    btnBolus.isEnabled = true
                }
            }
        }
    }
}
