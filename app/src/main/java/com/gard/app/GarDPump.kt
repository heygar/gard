package com.gard.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jwoglom.pumpx2.pump.bluetooth.PumpReadyState
import com.jwoglom.pumpx2.pump.TandemError
import com.welie.blessed.HciStatus
import com.welie.blessed.BluetoothPeripheral
import android.bluetooth.le.ScanResult
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler
import com.jwoglom.pumpx2.pump.bluetooth.TandemPump
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.bluetooth.PumpStateSupplier
import com.jwoglom.pumpx2.pump.messages.builders.CurrentBatteryRequestBuilder
import com.jwoglom.pumpx2.pump.messages.builders.LastBolusStatusRequestBuilder
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionRequest
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.*
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse
import com.jwoglom.pumpx2.pump.messages.response.control.InitiateBolusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.*
import com.jwoglom.pumpx2.pump.messages.response.historyLog.DexcomG6CGMHistoryLog
import com.jwoglom.pumpx2.pump.messages.response.historyLog.DexcomG7CGMHistoryLog
import com.jwoglom.pumpx2.pump.messages.response.historyLog.HistoryLogStreamResponse
import com.jwoglom.pumpx2.pump.messages.helpers.Dates
import java.util.Locale
import kotlin.math.*

data class ExtendedBolusSession(
    val id: Int,
    val totalUnits: Double,
    val totalMinutes: Int,
    val unitsNow: Double,
    var deliveredUnits: Double = 0.0,
    var remainingMinutes: Int
) {
    val extendedVolume: Double = totalUnits - unitsNow
    val pulseSize: Double = if (totalMinutes > 0) extendedVolume / max(1.0, ceil(totalMinutes / 2.0)) else 0.0
}

interface PumpUpdateListener {
    fun appendLog(msg: String)
    fun updateStatus(status: String)
    fun updateBattery(percent: Int)
    fun updateIOB(iob: Double)
    fun updateInsulin(units: Int)
    fun updateCGM(glucose: Int, trend: String, timestamp: Long = 0L)
    fun updateSessionSummaryMulti(lines: List<String>)
    fun updateExtendedNotification(total: Double, delivered: Double, remainingMins: Int)
    fun cancelExtendedNotification()
    fun dismissDeliveryDialog(force: Boolean)
    fun getPendingContributions(): Map<Int, Double>
    fun setPendingContributions(contribs: Map<Int, Double>)
    fun markBolusReady()
    fun uploadGlucoseMulti(entries: List<NightscoutClient.GlucoseEntry>)
}

class GarDPump(context: Context) : TandemPump(context) {

    var callback: PumpUpdateListener? = null
    var pairingCode: String = ""
    var connectedPeripheral: BluetoothPeripheral? = null
    var pendingVolumeNow: Long = 0L
    
    var pendingIsMicroPulse: Boolean = false
    var pendingBolusWaiting: Boolean = false
    var isPumpCurrentlyDelivering: Boolean = false
    @Volatile
    var isSimulatorMode: Boolean = false

    private val activeSessions = mutableListOf<ExtendedBolusSession>()
    private var nextSessionId = 1
    private var lastPulseTickTime: Long = 0

    private val bolusIdToSessionId = java.util.concurrent.ConcurrentHashMap<Int, Int>() 
    private val bolusIdToContributions = java.util.concurrent.ConcurrentHashMap<Int, Map<Int, Double>>()
    private val processedBolusIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    private var lastHistorySequenceNum: Long = -1

    private fun getDummyPeripheral(): BluetoothPeripheral {
        return TandemBluetoothHandler.getInstance(context, this).central.getPeripheral("00:00:00:00:00:00")
    }

    override fun sendCommand(peripheral: BluetoothPeripheral, message: Message) {
        if (isSimulatorMode) {
            callback?.appendLog("SIM: Intercepted ${message.javaClass.simpleName}")
            simulatePumpResponse(peripheral, message)
            return
        }
        super.sendCommand(peripheral, message)
    }

    private fun simulatePumpResponse(peripheral: BluetoothPeripheral, request: Message) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
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
                is LastBolusStatusV2Request -> LastBolusStatusV2Response(0, 123, 1000L, 1000L, 3, 8, 8, 0L, 1000L)
                is CurrentEGVGuiDataRequest -> CurrentEGVGuiDataResponse(1000L, 150, 1, 0)
                is HistoryLogStatusRequest -> HistoryLogStatusResponse(100, 0, 100)
                is HistoryLogRequest -> HistoryLogResponse(0, 1)
                else -> null
            }
            response?.let { onReceiveMessage(peripheral, it) }
        }
    }

    fun startSimulator() {
        this.isSimulatorMode = true
        callback?.appendLog("--- SIMULATOR ENABLED ---")
        onPumpConnected(getDummyPeripheral())
    }

    override fun onPumpDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult?, readyState: PumpReadyState): Boolean {
        callback?.appendLog("Discovered: ${peripheral.name}")
        return true
    }

    override fun onInitialPumpConnection(peripheral: BluetoothPeripheral) {
        if (isSimulatorMode) return
        val savedCode = com.jwoglom.pumpx2.pump.PumpState.getPairingCode(context) ?: ""
        val effectiveCode = pairingCode.ifBlank { savedCode }
        if (effectiveCode.length == 6) {
            onWaitingForPairingCode(peripheral, null)
        } else {
            super.onInitialPumpConnection(peripheral)
        }
    }

    override fun onPumpConnected(peripheral: BluetoothPeripheral) {
        if (!isSimulatorMode) super.onPumpConnected(peripheral)
        this.connectedPeripheral = peripheral
        callback?.updateStatus("Connected & Initialized!")
    }

    override fun onReceiveQualifyingEvent(peripheral: BluetoothPeripheral, events: MutableSet<com.jwoglom.pumpx2.pump.messages.response.qualifyingEvent.QualifyingEvent>) {
        // Only request history on peeps, not a full status sweep
        sendCommand(peripheral, HistoryLogStatusRequest())
        
        // If we have an active extended bolus, we might want to check progress too
        if (activeSessions.isNotEmpty()) {
            sendCommand(peripheral, CurrentBolusStatusRequest())
        }
    }

    override fun onWaitingForPairingCode(peripheral: BluetoothPeripheral, centralChallenge: com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractCentralChallengeResponse?) {
        if (isSimulatorMode) return
        val savedCode = com.jwoglom.pumpx2.pump.PumpState.getPairingCode(context) ?: ""
        val effectiveCode = pairingCode.ifBlank { savedCode }
        if (effectiveCode.isNotBlank()) this.pair(peripheral, centralChallenge, effectiveCode)
    }

    override fun onPumpDisconnected(peripheral: BluetoothPeripheral, status: HciStatus): Boolean {
        if (isSimulatorMode) return false
        this.connectedPeripheral = null
        callback?.updateStatus("Disconnected")
        return super.onPumpDisconnected(peripheral, status)
    }

    override fun onReceiveMessage(peripheral: BluetoothPeripheral, message: Message) {
        //callback?.appendLog("Message: $message")
        when (message) {
            is TimeSinceResetResponse -> {
                callback?.updateStatus("Connected & Initialized!")
                requestRealtimeStatus()
            }
            is CurrentBatteryAbstractResponse -> {
                callback?.updateBattery(message.batteryPercent)
            }
            is ControlIQIOBResponse -> {
                callback?.updateIOB(message.pumpDisplayedIOB / 1000.0)
            }
            is InsulinStatusResponse -> {
                callback?.updateInsulin(message.currentInsulinAmount)
            }
            is CurrentEGVGuiDataResponse -> {
                val trend = when (message.trendRate) {
                    3 -> "DoubleUp"
                    2 -> "SingleUp"
                    1 -> "FortyFiveUp"
                    0 -> "Flat"
                    -1 -> "FortyFiveDown"
                    -2 -> "SingleDown"
                    -3 -> "DoubleDown"
                    else -> "NONE"
                }
                if (message.cgmReading in 40..400) {
                    val pumpTimestamp = Dates.fromJan12008ToUnixEpochSeconds(message.getBgReadingTimestampSeconds()) * 1000
                    callback?.updateCGM(message.cgmReading, trend, pumpTimestamp)
                } else {
                    callback?.appendLog("Pump CGM reading ${message.cgmReading} is outside 40-400 range, ignoring.")
                }
            }
            is HistoryLogStatusResponse -> {
                val startSeq = if (lastHistorySequenceNum == -1L) {
                    message.lastSequenceNum - 50 // Just get last 50 entries initially
                } else {
                    lastHistorySequenceNum + 1
                }
                
                if (startSeq <= message.lastSequenceNum) {
                    val count = (message.lastSequenceNum - startSeq + 1).coerceAtMost(20).toInt()
                    if (count > 0) {
                        sendCommand(peripheral, HistoryLogRequest(max(0, startSeq), count))
                    }
                }
            }
            is HistoryLogStreamResponse -> {
                val entries = mutableListOf<NightscoutClient.GlucoseEntry>()
                message.historyLogs.forEach { log ->
                    lastHistorySequenceNum = max(lastHistorySequenceNum, log.sequenceNum)
                    
                    val timestamp = Dates.fromJan12008ToUnixEpochSeconds(log.pumpTimeSec) * 1000
                    
                    if (log is DexcomG6CGMHistoryLog) {
                        entries.add(NightscoutClient.GlucoseEntry(log.currentGlucoseDisplayValue, timestamp, ""))
                    } else if (log is DexcomG7CGMHistoryLog) {
                        entries.add(NightscoutClient.GlucoseEntry(log.currentGlucoseDisplayValue, timestamp, ""))
                    } else if (log.typeId() == 219 || log.typeId() == 264) {
                        // Libre 3+ likely uses these IDs. We log them for investigation.
                        callback?.appendLog("Detected Libre3+ History Log (${log.typeId()}): ${log.cargo.joinToString(",")}")
                    }
                }
                if (entries.isNotEmpty()) {
                    callback?.uploadGlucoseMulti(entries)
                }
            }
            is BolusPermissionResponse -> {
                if (message.status == 0) {
                    val bolusId = message.bolusId
                    if (pendingIsMicroPulse) {
                        bolusIdToContributions[bolusId] = callback?.getPendingContributions() ?: emptyMap()
                    } else {
                        synchronized(activeSessions) {
                            activeSessions.lastOrNull()?.let { bolusIdToSessionId[bolusId] = it.id }
                        }
                    }
                    val req = InitiateBolusRequest(this.pendingVolumeNow, bolusId, 8, 0L, 0L, 0, 0, 0)
                    sendCommand(peripheral, req)
                } else {
                    callback?.dismissDeliveryDialog(true)
                }
            }
            is CurrentBolusStatusResponse -> {
                isPumpCurrentlyDelivering = (message.status == CurrentBolusStatusResponse.CurrentBolusStatus.DELIVERING ||
                                            message.status == CurrentBolusStatusResponse.CurrentBolusStatus.REQUESTING)
                if (!isPumpCurrentlyDelivering) {
                    callback?.dismissDeliveryDialog(false)
                }
            }
            is LastBolusStatusAbstractResponse -> {
                val bid = message.bolusId
                if (bid != 0 && !processedBolusIds.contains(bid)) {
                    val actualUnits = message.deliveredVolume / 1000.0
                    if (bolusIdToContributions.containsKey(bid)) {
                        val contributions = bolusIdToContributions[bid]!!
                        val requestedTotal = contributions.values.sum()
                        val ratio = if (requestedTotal > 0) actualUnits / requestedTotal else 1.0
                        synchronized(activeSessions) {
                            contributions.forEach { (sid, amount) ->
                                activeSessions.find { it.id == sid }?.let { it.deliveredUnits += (amount * ratio) }
                            }
                        }
                        bolusIdToContributions.remove(bid)
                        processedBolusIds.add(bid)
                    } else if (bolusIdToSessionId.containsKey(bid)) {
                        val sid = bolusIdToSessionId[bid]!!
                        synchronized(activeSessions) {
                            activeSessions.find { it.id == sid }?.let { it.deliveredUnits = actualUnits }
                        }
                        bolusIdToSessionId.remove(bid)
                        processedBolusIds.add(bid)
                    }
                    updateUI()
                }
            }
        }
    }
    
    private var lastFullPollTime = 0L

    fun requestRealtimeStatus(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastFullPollTime < 60000)) {
            // Skip redundant poll to save battery
            return
        }
        lastFullPollTime = now

        val peripheral = this.connectedPeripheral ?: if (isSimulatorMode) getDummyPeripheral() else return
        val apiVer = PumpStateSupplier.pumpApiVersion?.get()
        if (apiVer != null) {
            sendCommand(peripheral, CurrentBatteryRequestBuilder.create(apiVer))
            sendCommand(peripheral, LastBolusStatusRequestBuilder.create(apiVer))
        }
        sendCommand(peripheral, ControlIQIOBRequest())
        sendCommand(peripheral, InsulinStatusRequest())
        sendCommand(peripheral, CurrentBolusStatusRequest())
        sendCommand(peripheral, CurrentEGVGuiDataRequest())
        sendCommand(peripheral, HistoryLogStatusRequest())
        
        if (pendingBolusWaiting && !pendingIsMicroPulse) {
            pendingBolusWaiting = false
            sendCommand(peripheral, BolusPermissionRequest())
        } else if (activeSessions.isNotEmpty()) {
            triggerNextMicroBolus()
        }
    }
    
    fun triggerNextMicroBolus() {
        val peripheral = this.connectedPeripheral ?: if (isSimulatorMode) getDummyPeripheral() else null
        if (peripheral == null) return

        val now = System.currentTimeMillis()
        if (now - lastPulseTickTime < 110000 && !isSimulatorMode) return 

        var combinedPulse = 0.0
        val currentContributions = mutableMapOf<Int, Double>()
        
        synchronized(activeSessions) {
            if (activeSessions.isEmpty() || isPumpCurrentlyDelivering) return
            
            val iterator = activeSessions.iterator()
            while (iterator.hasNext()) {
                val session = iterator.next()
                val remainingToExtend = session.totalUnits - session.deliveredUnits
                if (session.remainingMinutes <= 0 || remainingToExtend < 0.01) {
                    iterator.remove()
                    if (activeSessions.isEmpty()) callback?.markBolusReady()
                    continue
                }
                val minutesThisTick = if (session.remainingMinutes >= 2) 2 else session.remainingMinutes
                val sessionPulse = if (session.remainingMinutes <= minutesThisTick) remainingToExtend else min(session.pulseSize, remainingToExtend)
                if (sessionPulse > 0) {
                    combinedPulse += sessionPulse
                    currentContributions[session.id] = sessionPulse
                }
            }

            if (combinedPulse < 0.05) {
                if (activeSessions.isEmpty()) callback?.markBolusReady()
                return
            }
            
            lastPulseTickTime = now
            activeSessions.forEach { s -> if (currentContributions.containsKey(s.id)) s.remainingMinutes -= 2 }
        }
        
        this.pendingVolumeNow = (combinedPulse * 1000).toLong()
        this.pendingIsMicroPulse = true
        callback?.setPendingContributions(currentContributions)
        sendCommand(peripheral, BolusPermissionRequest())
    }

    private fun updateUI() {
        val sessionStrings = synchronized(activeSessions) {
            activeSessions.map {
                val elapsed = max(0, it.totalMinutes - it.remainingMinutes)
                String.format(Locale.getDefault(), "Bolus #%d: %.1f / %.1f U (%d/%d min)", it.id, it.deliveredUnits, it.totalUnits, elapsed, it.totalMinutes)
            }
        }
        
        callback?.updateSessionSummaryMulti(sessionStrings)
        synchronized(activeSessions) {
            if (activeSessions.isNotEmpty()) {
                val s = activeSessions[0]
                callback?.updateExtendedNotification(s.totalUnits, s.deliveredUnits, s.remainingMinutes)
            } else {
                callback?.cancelExtendedNotification()
            }
        }
    }

    fun sendBolus(totalUnits: Double, extMin: Int, unitsNow: Double) {
        val effectiveExtMin = if (extMin <= 0) 2 else extMin
        val effectiveUnitsNow = if (extMin <= 0 && unitsNow <= 0) totalUnits else unitsNow
        
        enableActionsAffectingInsulinDelivery()
        synchronized(activeSessions) {
            if (totalUnits > 10.0 || activeSessions.size >= 3) {
                callback?.appendLog("Bolus rejected: invalid units or too many active sessions")
                return
            }
            val session = ExtendedBolusSession(nextSessionId++, totalUnits, effectiveExtMin, effectiveUnitsNow, 0.0, effectiveExtMin)
            activeSessions.add(session)
            if (nextSessionId > 10) nextSessionId = 1
            if (activeSessions.size == 1) this.lastPulseTickTime = 0
        }
        
        if (effectiveUnitsNow > 0) {
            this.pendingVolumeNow = (effectiveUnitsNow * 1000).toLong()
            this.pendingIsMicroPulse = false
            this.pendingBolusWaiting = true
            val peripheral = this.connectedPeripheral ?: if (isSimulatorMode) getDummyPeripheral() else null
            if (peripheral != null) {
                pendingBolusWaiting = false
                sendCommand(peripheral, BolusPermissionRequest())
            }
        } else {
            updateUI()
            triggerNextMicroBolus()
        }
    }

    fun cancelExtendedBolus() {
        synchronized(activeSessions) {
            activeSessions.clear()
        }
        bolusIdToContributions.clear()
        bolusIdToSessionId.clear()
        this.lastPulseTickTime = 0
        callback?.appendLog("Bolus Canceled by user.")
        callback?.cancelExtendedNotification()
        callback?.updateSessionSummaryMulti(listOf("CANCELED"))
        
        // Hide the progress layout after a short delay
        Handler(Looper.getMainLooper()).postDelayed({
            callback?.updateSessionSummaryMulti(emptyList())
        }, 3000)
    }
}
