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
import androidx.biometric.BiometricPrompt

class MainActivity : AppCompatActivity() {

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

        myPump = GarDPump(this, this)
        
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                appendLog(message)
            }
        })

        btnConnect.setOnClickListener {
            myPump.pairingCode = etPairingCode.text.toString().trim()
            checkPermissionsAndStart()
        }

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GarD Logs", tvLog.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs Copied to Clipboard!", Toast.LENGTH_SHORT).show()
        }
        
        btnBolus.setOnClickListener {
            val unitsStr = etBolusUnits.text.toString()
            val units = unitsStr.toDoubleOrNull() ?: 0.0
            
            if (units <= 0.0 || units > 5.0) {
                Toast.makeText(this, "Invalid units! Must be between 0.1 and 5.0", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            showBiometricPrompt(units)
        }

        if (hasPermissions()) {
            appendLog("Auto-starting Bluetooth since permissions are granted...")
            startBluetooth()
        }
    }

    private fun hasPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            startBluetooth()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBluetooth()
        } else {
            tvStatus.text = "Status: Permissions Denied"
        }
    }

    private fun startBluetooth() {
        tvStatus.text = "Status: Scanning..."
        appendLog("Starting BT Scanner with pairing code: ${myPump.pairingCode}")
        try {
            bluetoothHandler = TandemBluetoothHandler.getInstance(applicationContext, myPump)
            bluetoothHandler?.startScan()
        } catch (e: Exception) {
            appendLog("Error starting scan: ${e.message}")
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
                // Schedule the next poll in 2 minutes
                pollingHandler.postDelayed(this, 2 * 60 * 1000)
            }
        }
        // It's already requested right after connect in GarDPump, 
        // so we just queue it for 2 minutes from now
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
        val text = if (cgm > 0) "CGM: $cgm mg/dL" else "CGM: NA"
        findViewById<TextView>(R.id.tvCGM).text = text
    }

    private fun showBiometricPrompt(units: Double) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    appendLog("Biometric error: $errString")
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    appendLog("Biometric Success! Transmitting $units units.")
                    showDeliveryDialog(units)
                    myPump.sendBolus(units)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    appendLog("Biometric failed!")
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirm Bolus")
            .setSubtitle("Authenticate to deliver $units U")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private var deliveryDialog: AlertDialog? = null
    private val deliveryTimeoutHandler = Handler(Looper.getMainLooper())
    private val deliveryTimeoutRunnable = Runnable { dismissDeliveryDialog(true) }
    private var deliveryStartTime = 0L

    fun showDeliveryDialog(units: Double) {
        if (deliveryDialog != null && deliveryDialog!!.isShowing) return
        
        deliveryStartTime = System.currentTimeMillis()
        onBolusDeliveryStarted()

        val builder = AlertDialog.Builder(this)
        
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(80, 80, 80, 80)
        layout.setBackgroundColor(Color.parseColor("#4CAF50")) // Green background
        layout.gravity = Gravity.CENTER
        
        val tv = TextView(this)
        tv.text = "Delivering $units units...\n\nPlease Wait."
        tv.setTextColor(Color.WHITE)
        tv.textSize = 24f
        tv.gravity = Gravity.CENTER
        tv.setTypeface(null, android.graphics.Typeface.BOLD)
        
        layout.addView(tv)
        builder.setView(layout)
        builder.setCancelable(false)
        
        deliveryDialog = builder.create()
        deliveryDialog?.show()
        
        // Timeout 60 seconds maximum
        deliveryTimeoutHandler.removeCallbacks(deliveryTimeoutRunnable)
        deliveryTimeoutHandler.postDelayed(deliveryTimeoutRunnable, 60000)
    }

    fun dismissDeliveryDialog(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - deliveryStartTime < 5000) {
            return // Ignore "not delivering" messages if we literally just asked to bolus <5 seconds ago
        }
        
        deliveryDialog?.let {
            if (it.isShowing) {
                it.dismiss()
            }
        }
        deliveryDialog = null
        deliveryTimeoutHandler.removeCallbacks(deliveryTimeoutRunnable)
        onBolusDeliveryEnded()
    }

    fun onBolusDeliveryStarted() {
        val btnBolus: Button = findViewById(R.id.btnBolus)
        val etBolusUnits: EditText = findViewById(R.id.etBolusUnits)
        btnBolus.text = "DELIVERING..."
        btnBolus.isEnabled = false
        etBolusUnits.setText("")
        etBolusUnits.isEnabled = false
    }

    fun onBolusDeliveryEnded() {
        val btnBolus: Button = findViewById(R.id.btnBolus)
        val etBolusUnits: EditText = findViewById(R.id.etBolusUnits)
        btnBolus.text = "BOLUS"
        btnBolus.isEnabled = true
        etBolusUnits.isEnabled = true
    }
}
