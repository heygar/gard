package com.gard.app

import android.content.Context
import com.jwoglom.pumpx2.pump.bluetooth.PumpReadyState
import com.welie.blessed.HciStatus
import com.welie.blessed.BluetoothPeripheral
import android.bluetooth.le.ScanResult
import android.view.View
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler
import com.jwoglom.pumpx2.pump.bluetooth.TandemPump
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.bluetooth.PumpStateSupplier
import com.jwoglom.pumpx2.pump.messages.builders.CurrentBatteryRequestBuilder
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQIOBRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.InsulinStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.TimeSinceResetResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBatteryAbstractResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ControlIQIOBResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.InsulinStatusResponse

class GarDPump(context: Context, val activity: MainActivity) : TandemPump(context) {

    var pairingCode: String = ""
    var connectedPeripheral: BluetoothPeripheral? = null
    var pendingBolusVolume: Long = 0L

    override fun onPumpDiscovered(
        peripheral: BluetoothPeripheral,
        scanResult: ScanResult?,
        readyState: PumpReadyState
    ): Boolean {
        activity.appendLog("Discovered: ${peripheral.name} - ${peripheral.address}")
        
        val savedCode = com.jwoglom.pumpx2.pump.PumpState.getPairingCode(activity) ?: ""
        val effectiveCode = if (pairingCode.isNotBlank()) pairingCode else savedCode
        
        if (effectiveCode.length == 6) {
            val handler = TandemBluetoothHandler.getInstance(activity.applicationContext, this)
            if (peripheral.bondState != com.welie.blessed.BondState.BONDED) {
                handler.central.setPinCodeForPeripheral(peripheral.address, effectiveCode)
                activity.appendLog("Injected Passkey into OS Auto-Paring: $effectiveCode")
            }
        }
        // Return true to connect
        return true
    }

    override fun onInitialPumpConnection(peripheral: BluetoothPeripheral) {
        activity.appendLog("Initial Pump Connection Hook Fired")
        
        val savedCode = com.jwoglom.pumpx2.pump.PumpState.getPairingCode(activity) ?: ""
        val effectiveCode = if (pairingCode.isNotBlank()) pairingCode else savedCode
        
        if (effectiveCode.length == 6) {
            // New v7.7 firmware or Mobi pump! 
            // DO NOT call super.onInitialPumpConnection(peripheral) because it sends the legacy 
            // CentralChallengeRequest intended for old pumps, which causes the pump to abort!
            activity.appendLog("6-Digit Code Detected. Bypassing Legacy 16-Char CentralChallengeRequest.")
            onWaitingForPairingCode(peripheral, null)
        } else {
            super.onInitialPumpConnection(peripheral)
        }
    }

    override fun onPumpConnected(peripheral: BluetoothPeripheral) {
        super.onPumpConnected(peripheral)
        this.connectedPeripheral = peripheral
        activity.appendLog("Connected: ${peripheral.address}")
    }



    override fun onReceiveQualifyingEvent(
        peripheral: BluetoothPeripheral,
        events: MutableSet<com.jwoglom.pumpx2.pump.messages.response.qualifyingEvent.QualifyingEvent>
    ) {
        activity.appendLog("Qualifying Event: $events")
        // A Qualifying Event (like bolus start/stop) means we must refresh the UI immediately!
        requestRealtimeStatus()
    }

    override fun onWaitingForPairingCode(
        peripheral: BluetoothPeripheral,
        centralChallenge: com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractCentralChallengeResponse?
    ) {
        val savedCode = com.jwoglom.pumpx2.pump.PumpState.getPairingCode(activity) ?: ""
        val effectiveCode = if (pairingCode.isNotBlank()) pairingCode else savedCode
        
        activity.appendLog("Waiting for Pairing Code")
        if (effectiveCode.isNotBlank()) {
            activity.appendLog("Submitting Pairing Code: $effectiveCode")
            this.pair(peripheral, centralChallenge, effectiveCode)
        } else {
            activity.appendLog("NO PAIRING CODE PROVIDED! App will loop connection requests.")
        }
    }

    override fun onPumpDisconnected(peripheral: BluetoothPeripheral, status: HciStatus): Boolean {
        activity.appendLog("Disconnected: $status")
        this.connectedPeripheral = null
        activity.runOnUiThread { activity.updateStatus("Disconnected") }
        
        // If the pump gracefully killed the connection, it usually means our OS bond is stale/corrupt!
        // The default pumpX2 logic instantly reconnects endlessly. We need to break the loop and nuke the bond.
        if (status == HciStatus.REMOTE_USER_TERMINATED_CONNECTION || status == HciStatus.CONNECTION_TERMINATED_BY_LOCAL_HOST) {
            if (peripheral.bondState == com.welie.blessed.BondState.BONDED) {
                activity.appendLog("Pump rejected our old keys. Nuking stale Android OS bond!!")
                val handler = TandemBluetoothHandler.getInstance(activity.applicationContext, this)
                handler.central.removeBond(peripheral.address)
                // Returning false breaks the blessed-android auto-reconnect loop.
                return false
            } else {
                 // Even if not bonded, don't infinitely rapid-fire reconnect if the pump explicitly refuses us.
                 return false
            }
        }

        return super.onPumpDisconnected(peripheral, status)
    }

    override fun onReceiveMessage(peripheral: BluetoothPeripheral, message: Message) {
        activity.appendLog("Message: $message")
        when (message) {
            is TimeSinceResetResponse -> {
                activity.runOnUiThread { 
                    activity.updateStatus("Connected & Initialized!") 
                }
                
                // Now safe to ask for battery and IOB!
                val apiVer = PumpStateSupplier.pumpApiVersion?.get()
                if (apiVer != null) {
                    sendCommand(peripheral, CurrentBatteryRequestBuilder.create(apiVer))
                    sendCommand(peripheral, ControlIQIOBRequest())
                    sendCommand(peripheral, InsulinStatusRequest())
                }
            }
            is com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBatteryAbstractResponse -> {
                val pct = message.batteryPercent
                activity.runOnUiThread { activity.updateBattery(pct) }
            }
            is ControlIQIOBResponse -> {
                val iob = message.pumpDisplayedIOB / 1000.0
                activity.runOnUiThread { activity.updateIOB(iob) }
            }
            is InsulinStatusResponse -> {
                activity.appendLog("Parsed Insulin Remaining: ${message.currentInsulinAmount}")
                activity.runOnUiThread { activity.updateInsulin(message.currentInsulinAmount) }
            }
            is com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentEGVGuiDataResponse -> {
                activity.appendLog("Parsed CGM: ${message.cgmReading}")
                activity.runOnUiThread { activity.updateCGM(message.cgmReading) }
            }
            is com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse -> {
                if (message.status == 0) {
                    val bolusId = message.bolusId
                    activity.appendLog("Bolus Permission Granted! ID: $bolusId")
                    val req = com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest(
                        this.pendingBolusVolume,
                        bolusId,
                        com.jwoglom.pumpx2.pump.messages.response.historyLog.BolusDeliveryHistoryLog.BolusType.toBitmask(
                            com.jwoglom.pumpx2.pump.messages.response.historyLog.BolusDeliveryHistoryLog.BolusType.FOOD2
                        ),
                        0L, 0L, 0, 0, 0
                    )
                    sendCommand(connectedPeripheral!!, req)
                    activity.appendLog("Bolus Transmitted to Pump.")
                } else {
                    activity.appendLog("Bolus Permission DENIED by pump (Status ${message.status})")
                }
            }
            is com.jwoglom.pumpx2.pump.messages.response.control.InitiateBolusResponse -> {
                activity.appendLog("InitiateBolusResponse received: Status ${message.status}")
                if (message.status != 0) {
                    activity.runOnUiThread { activity.dismissDeliveryDialog(true) }
                }
            }
            is com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse -> {
                val status = message.status
                if (status == com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse.CurrentBolusStatus.DELIVERING ||
                    status == com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse.CurrentBolusStatus.REQUESTING) {
                    // It is officially delivering, popup is already showing!
                } else {
                    activity.runOnUiThread { activity.dismissDeliveryDialog(false) }
                }
            }
        }
    }
    
    fun requestRealtimeStatus() {
        val peripheral = this.connectedPeripheral ?: return
        val apiVer = PumpStateSupplier.pumpApiVersion?.get() ?: return
        sendCommand(peripheral, CurrentBatteryRequestBuilder.create(apiVer))
        sendCommand(peripheral, ControlIQIOBRequest())
        sendCommand(peripheral, InsulinStatusRequest())
        sendCommand(peripheral, com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentEGVGuiDataRequest())
        
        // Check bolus status
        sendCommand(peripheral, com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest())
    }
    
    fun sendBolus(units: Double) {
        if (units > 5.0) {
            activity.appendLog("BOLUS DENIED: Exceeds 5.0 unit max!")
            return
        }
        
        enableActionsAffectingInsulinDelivery()
        
        val volume = (units * 1000).toLong()
        this.pendingBolusVolume = volume
        activity.appendLog("Requesting Bolus Permission for $units U")
        
        val peripheral = this.connectedPeripheral ?: return
        sendCommand(peripheral, com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionRequest())
    }
}
