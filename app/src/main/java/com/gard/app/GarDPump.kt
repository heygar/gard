package com.gard.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jwoglom.pumpx2.pump.bluetooth.PumpReadyState
import com.welie.blessed.HciStatus
import com.welie.blessed.BluetoothPeripheral
import android.bluetooth.le.ScanResult
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler
import com.jwoglom.pumpx2.pump.bluetooth.TandemPump
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.bluetooth.PumpStateSupplier
import com.jwoglom.pumpx2.pump.messages.builders.CurrentBatteryRequestBuilder
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionRequest
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.*
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse
import com.jwoglom.pumpx2.pump.messages.response.control.InitiateBolusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.*
import java.util.Locale
import kotlin.math.*

class GarDPump(context: Context, val activity: MainActivity) : TandemPump(context) {

    var pairingCode: String = ""
    var connectedPeripheral: BluetoothPeripheral? = null
    var pendingVolumeNow: Long = 0L
    
    // Distinguish between the user's "Now" bolus and the background "Micro" pulses
    var pendingIsMicroPulse: Boolean = false
    var isPumpCurrentlyDelivering: Boolean = false
    var isSimulatorMode: Boolean = false

    // Active extended bolus queue
    var extTotalUnits: Double = 0.0
    var extDeliveredUnits: Double = 0.0
    var extRemainingMinutes: Int = 0
    var extPulseSize: Double = 0.0
    var extIsDelivering: Boolean = false
    var lastPulseTickTime: Long = 0

    // Session-level accumulators (never reset between boluses)
    var sessionTotalUnits: Double = 0.0
    var sessionTotalMinutes: Int = 0
    var sessionDeliveredUnits: Double = 0.0
    var sessionElapsedMinutes: Int = 0

    private fun getDummyPeripheral(): BluetoothPeripheral {
        return TandemBluetoothHandler.getInstance(activity, this).central.getPeripheral("00:00:00:00:00:00")
    }

    /**
     * DUMMY/MOCK MODE: Overriding sendCommand intercepts pump comms 
     * without modifying the library project.
     */
    override fun sendCommand(peripheral: BluetoothPeripheral, message: Message) {
        if (isSimulatorMode) {
            activity.appendLog("SIM: Intercepted ${message.javaClass.simpleName}")
            simulatePumpResponse(peripheral, message)
            return
        }
        super.sendCommand(peripheral, message)
    }

    private fun simulatePumpResponse(peripheral: BluetoothPeripheral, request: Message) {
        val handler = Handler(Looper.getMainLooper())
        // Simulate BT latency
        handler.postDelayed({
            val response: Message? = when (request) {
                is ApiVersionRequest -> ApiVersionResponse(1, 1)
                is TimeSinceResetRequest -> TimeSinceResetResponse(1000, 1000)
                is CurrentBatteryV1Request -> CurrentBatteryV1Response(85, 85)
                is CurrentBatteryV2Request -> CurrentBatteryV2Response(85, 85, 0, 0, 0, 0, 0)
                is ControlIQIOBRequest -> ControlIQIOBResponse(1500, 1500, 1500, 1500, 3600)
                is InsulinStatusRequest -> InsulinStatusResponse(120, 0, 0)
                is BolusPermissionRequest -> BolusPermissionResponse(0, (1000..9999).random(), 0)
                is InitiateBolusRequest -> {
                    isPumpCurrentlyDelivering = true
                    // Simulate completion after 3 seconds
                    handler.postDelayed({ isPumpCurrentlyDelivering = false }, 3000)
                    InitiateBolusResponse(0, request.bolusID, 0)
                }
                is CurrentBolusStatusRequest -> {
                    val statusId = if (isPumpCurrentlyDelivering) 
                        CurrentBolusStatusResponse.CurrentBolusStatus.DELIVERING.id 
                    else 
                        CurrentBolusStatusResponse.CurrentBolusStatus.ALREADY_DELIVERED_OR_INVALID.id
                    CurrentBolusStatusResponse(statusId, 0, 0L, 0L, 0, 0)
                }
                else -> null
            }
            response?.let { 
                onReceiveMessage(peripheral, it) 
            }
        }, 500)
    }

    fun startSimulator() {
        this.isSimulatorMode = true
        activity.appendLog("--- SIMULATOR ENABLED ---")
        // Manually trigger the "Connected" lifecycle
        onPumpConnected(getDummyPeripheral())
    }

    override fun onPumpDiscovered(
        peripheral: BluetoothPeripheral,
        scanResult: ScanResult?,
        readyState: PumpReadyState
    ): Boolean {
        activity.appendLog("Discovered: ${peripheral.name}")
        return true
    }

    override fun onInitialPumpConnection(peripheral: BluetoothPeripheral) {
        if (isSimulatorMode) return
        activity.appendLog("Initial Pump Connection Hook Fired")
        
        val savedCode = com.jwoglom.pumpx2.pump.PumpState.getPairingCode(activity) ?: ""
        val effectiveCode = pairingCode.ifBlank { savedCode }
        
        if (effectiveCode.length == 6) {
            activity.appendLog("6-Digit Code Detected. Bypassing Legacy 16-Char CentralChallengeRequest.")
            onWaitingForPairingCode(peripheral, null)
        } else {
            super.onInitialPumpConnection(peripheral)
        }
    }

    override fun onPumpConnected(peripheral: BluetoothPeripheral) {
        if (!isSimulatorMode) super.onPumpConnected(peripheral)
        this.connectedPeripheral = peripheral
        activity.appendLog("Connected: ${peripheral.address}")
        activity.runOnUiThread { 
            activity.updateStatus("Connected & Initialized!") 
        }
    }

    override fun onReceiveQualifyingEvent(
        peripheral: BluetoothPeripheral,
        events: MutableSet<com.jwoglom.pumpx2.pump.messages.response.qualifyingEvent.QualifyingEvent>
    ) {
        activity.appendLog("Qualifying Event: $events")
        requestRealtimeStatus()
    }

    override fun onWaitingForPairingCode(
        peripheral: BluetoothPeripheral,
        centralChallenge: com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractCentralChallengeResponse?
    ) {
        if (isSimulatorMode) return
        val savedCode = com.jwoglom.pumpx2.pump.PumpState.getPairingCode(activity) ?: ""
        val effectiveCode = pairingCode.ifBlank { savedCode }
        
        activity.appendLog("Waiting for Pairing Code")
        if (effectiveCode.isNotBlank()) {
            activity.appendLog("Submitting Pairing Code: $effectiveCode")
            this.pair(peripheral, centralChallenge, effectiveCode)
        } else {
            activity.appendLog("NO PAIRING CODE PROVIDED! App will loop connection requests.")
        }
    }

    override fun onPumpDisconnected(peripheral: BluetoothPeripheral, status: HciStatus): Boolean {
        if (isSimulatorMode) return false
        activity.appendLog("Disconnected: $status")
        this.connectedPeripheral = null
        activity.runOnUiThread { activity.updateStatus("Disconnected") }
        
        if (status == HciStatus.REMOTE_USER_TERMINATED_CONNECTION || status == HciStatus.CONNECTION_TERMINATED_BY_LOCAL_HOST) {
            if (peripheral.bondState == com.welie.blessed.BondState.BONDED) {
                activity.appendLog("Pump rejected our old keys. Nuking stale Android OS bond!!")
                val handler = TandemBluetoothHandler.getInstance(activity.applicationContext, this)
                handler.central.removeBond(peripheral.address)
                return false
            } else {
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
                requestRealtimeStatus()
            }
            is CurrentBatteryAbstractResponse -> {
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
            is CurrentEGVGuiDataResponse -> {
                activity.runOnUiThread { activity.updateCGM(message.cgmReading) }
            }
            is BolusPermissionResponse -> {
                if (message.status == 0) {
                    val bolusId = message.bolusId
                    activity.appendLog("Bolus Permission Granted! ID: $bolusId")
                    val req = InitiateBolusRequest(
                        this.pendingVolumeNow,
                        bolusId,
                        com.jwoglom.pumpx2.pump.messages.response.historyLog.BolusDeliveryHistoryLog.BolusType.toBitmask(
                            com.jwoglom.pumpx2.pump.messages.response.historyLog.BolusDeliveryHistoryLog.BolusType.FOOD2
                        ),
                        0L, 0L, 0, 0, 0
                    )
                    sendCommand(peripheral, req)
                    activity.appendLog("Bolus Transmitted to Pump.")
                } else {
                    activity.appendLog("Bolus Permission DENIED by pump (Status ${message.status})")
                    activity.runOnUiThread { activity.dismissDeliveryDialog(true) }
                }
            }
            is InitiateBolusResponse -> {
                activity.appendLog("InitiateBolusResponse received: Status ${message.status}")
                if (message.status == 0) {
                    val deliveredNow = this.pendingVolumeNow / 1000.0
                    
                    // FIXED: Only credit to extended total if this was actually a micro-pulse!
                    if (extIsDelivering && pendingIsMicroPulse) {
                        extDeliveredUnits += deliveredNow
                    }
                    sessionDeliveredUnits += deliveredNow
                    
                    activity.runOnUiThread {
                        activity.updateExtendedNotification(extTotalUnits, extDeliveredUnits, extRemainingMinutes)
                        activity.updateSessionSummary(sessionDeliveredUnits, sessionTotalUnits, sessionElapsedMinutes, sessionTotalMinutes)
                    }
                } else {
                    activity.runOnUiThread { activity.dismissDeliveryDialog(true) }
                }
            }
            is CurrentBolusStatusResponse -> {
                val status = message.status
                val isDelivering = (status == CurrentBolusStatusResponse.CurrentBolusStatus.DELIVERING ||
                                  status == CurrentBolusStatusResponse.CurrentBolusStatus.REQUESTING)
                
                if (!isDelivering) {
                    activity.runOnUiThread { activity.dismissDeliveryDialog(false) }
                }
            }
        }
    }
    
    fun requestRealtimeStatus() {
        val peripheral = this.connectedPeripheral ?: if (isSimulatorMode) getDummyPeripheral() else return
        val apiVer = PumpStateSupplier.pumpApiVersion?.get()
        if (apiVer != null) {
            sendCommand(peripheral, CurrentBatteryRequestBuilder.create(apiVer))
        } else if (isSimulatorMode) {
             sendCommand(peripheral, CurrentBatteryV2Request())
        }
        sendCommand(peripheral, ControlIQIOBRequest())
        sendCommand(peripheral, InsulinStatusRequest())
        sendCommand(peripheral, CurrentBolusStatusRequest())
        
        if (extIsDelivering) {
            triggerNextMicroBolus()
        }
    }
    
    fun triggerNextMicroBolus() {
        if (!extIsDelivering) return
        
        // Throttle: Ensure at least 1m 50s has passed to avoid double-firing on Qualifying Events
        val now = System.currentTimeMillis()
        if (now - lastPulseTickTime < 110000 && !isSimulatorMode) return 

        if (extRemainingMinutes <= 0) {
            extIsDelivering = false
            activity.runOnUiThread { activity.cancelExtendedNotification() }
            return
        }
        
        val minutesThisTick = if (extRemainingMinutes >= 2) 2 else extRemainingMinutes
        
        val safePulse = if (extRemainingMinutes <= minutesThisTick) {
            // Last pulse: send exactly what's left
            extTotalUnits - extDeliveredUnits
        } else {
            min(extPulseSize, extTotalUnits - extDeliveredUnits)
        }.coerceAtLeast(0.0)
        
        if (safePulse < 0.05) { // Pump minimum bolus is 0.05U
            extIsDelivering = false
            activity.runOnUiThread { activity.cancelExtendedNotification() }
            return
        }
        
        lastPulseTickTime = now
        extRemainingMinutes -= minutesThisTick
        sessionElapsedMinutes += minutesThisTick
        
        this.pendingVolumeNow = (safePulse * 1000).toLong()
        this.pendingIsMicroPulse = true // Mark as micro-pulse
        activity.appendLog(String.format(Locale.getDefault(), "Ext Engine: Queuing micro pulse %.2fU. (%d min left)", safePulse, extRemainingMinutes))
        
        val peripheral = this.connectedPeripheral ?: if (isSimulatorMode) getDummyPeripheral() else null
        if (peripheral == null) {
            activity.appendLog("Ext Engine: BT dropped, pulse deferred.")
            extRemainingMinutes += minutesThisTick
            sessionElapsedMinutes -= minutesThisTick
            lastPulseTickTime = 0 
            return
        }
        sendCommand(peripheral, BolusPermissionRequest())
    }

    fun sendBolus(totalUnits: Double, extMin: Int, unitsNow: Double) {
        if (totalUnits > 10.0) {
            activity.appendLog("BOLUS DENIED: Exceeds 10.0 unit max!")
            return
        }
        
        enableActionsAffectingInsulinDelivery()
        
        val volumeExtended = totalUnits - unitsNow
        
        sessionTotalUnits += totalUnits
        sessionTotalMinutes += extMin
        
        if (extMin > 0 && volumeExtended > 0.0) {
            this.extTotalUnits = volumeExtended
            this.extDeliveredUnits = 0.0
            this.extRemainingMinutes = extMin
            this.extPulseSize = volumeExtended / max(1.0, ceil(extMin / 2.0))
            this.extIsDelivering = true
            this.lastPulseTickTime = System.currentTimeMillis() // Start timer now
            
            activity.appendLog(String.format(Locale.getDefault(), "Ext Engine: Armed. %.2fU over %d min. First pulse in 2 min.", volumeExtended, extMin))
            activity.runOnUiThread { 
                activity.updateExtendedNotification(extTotalUnits, 0.0, extRemainingMinutes)
                activity.updateSessionSummary(sessionDeliveredUnits, sessionTotalUnits, sessionElapsedMinutes, sessionTotalMinutes)
            }
        }
        
        if (unitsNow > 0) {
            this.pendingVolumeNow = (unitsNow * 1000).toLong()
            this.pendingIsMicroPulse = false // Mark as the "Now" part
            activity.appendLog(String.format(Locale.getDefault(), "Requesting immediate bolus: %.2fU", unitsNow))
            val peripheral = this.connectedPeripheral ?: if (isSimulatorMode) getDummyPeripheral() else null
            if (peripheral == null) return
            sendCommand(peripheral, BolusPermissionRequest())
        }
    }

    fun cancelExtendedBolus() {
        if (extIsDelivering) {
            activity.appendLog("Ext Engine: Extended Bolus Cancelled by User.")
            extIsDelivering = false
            extRemainingMinutes = 0
            activity.cancelExtendedNotification()
        }
    }
}
