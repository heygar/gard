package com.gard.app

import android.content.Context
import android.util.Log
import com.jwoglom.pumpx2.pump.bluetooth.TandemPump
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.bluetooth.PumpReadyState
import com.welie.blessed.BluetoothPeripheral
import android.bluetooth.le.ScanResult
import com.welie.blessed.HciStatus

class GarDPump(context: Context, val activity: MainActivity) : TandemPump(context) {

    var pairingCode: String = ""

    override fun onPumpDiscovered(
        peripheral: BluetoothPeripheral,
        scanResult: ScanResult?,
        readyState: PumpReadyState
    ): Boolean {
        activity.appendLog("Discovered: ${peripheral.name} - ${peripheral.address}")
        // Return true to connect
        return true
    }

    override fun onPumpConnected(peripheral: BluetoothPeripheral) {
        super.onPumpConnected(peripheral)
        activity.appendLog("Connected: ${peripheral.address}")
    }

    override fun onReceiveMessage(peripheral: BluetoothPeripheral, message: Message) {
        activity.appendLog("Message: $message")
    }

    override fun onReceiveQualifyingEvent(
        peripheral: BluetoothPeripheral,
        events: MutableSet<com.jwoglom.pumpx2.pump.messages.response.qualifyingEvent.QualifyingEvent>
    ) {
        activity.appendLog("Qualifying Event: $events")
    }

    override fun onWaitingForPairingCode(
        peripheral: BluetoothPeripheral,
        centralChallenge: com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractCentralChallengeResponse?
    ) {
        activity.appendLog("Waiting for Pairing Code")
        if (pairingCode.isNotBlank()) {
            activity.appendLog("Submitting Pairing Code: $pairingCode")
            this.pair(peripheral, centralChallenge, pairingCode)
        } else {
            activity.appendLog("NO PAIRING CODE PROVIDED! App will loop connection requests.")
        }
    }

    override fun onPumpDisconnected(peripheral: BluetoothPeripheral, status: HciStatus): Boolean {
        activity.appendLog("Disconnected: $status")
        return super.onPumpDisconnected(peripheral, status)
    }
}
