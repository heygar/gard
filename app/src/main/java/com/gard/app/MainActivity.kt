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

class MainActivity : AppCompatActivity() {

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

        tvStatus = findViewById(R.id.tvStatus)
        tvBattery = findViewById(R.id.tvBattery)
        etPairingCode = findViewById(R.id.etPairingCode)
        btnConnect = findViewById(R.id.btnConnect)
        tvLog = findViewById(R.id.tvLog)

        myPump = GarDPump(this, this)
        
        btnConnect.setOnClickListener {
            myPump.pairingCode = etPairingCode.text.toString().trim()
            checkPermissionsAndStart()
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
}
