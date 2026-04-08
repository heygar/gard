package com.jwoglom.pumpx2.pump.messages.response.historyLog;

import org.apache.commons.lang3.Validate;
import com.jwoglom.pumpx2.pump.messages.annotations.HistoryLogProps;
import com.jwoglom.pumpx2.pump.messages.helpers.Bytes;

@HistoryLogProps(
    opCode = 372,
    displayName = "CGM Data (FSL2)",
    internalName = "LID_CGM_DATA_FSL2"
)
public class CgmDataFsl2HistoryLog extends HistoryLog {
    
    private int glucoseValue;
    private long timestamp;

    public CgmDataFsl2HistoryLog() {}
    public CgmDataFsl2HistoryLog(long pumpTimeSec, long sequenceNum, int glucoseValue, long timestamp) {
        super(pumpTimeSec, sequenceNum);
        this.cargo = buildCargo(pumpTimeSec, sequenceNum, glucoseValue, timestamp);
        this.glucoseValue = glucoseValue;
        this.timestamp = timestamp;
    }

    public int typeId() {
        return 372;
    }

    public void parse(byte[] raw) {
        Validate.isTrue(raw.length == 26);
        this.cargo = raw;
        parseBase(raw);
        // Using common offsets for glucose/timestamp
        this.glucoseValue = Bytes.readShort(raw, 16);
        this.timestamp = Bytes.readUint32(raw, 18);
    }

    public static byte[] buildCargo(long pumpTimeSec, long sequenceNum, int glucoseValue, long timestamp) {
        return HistoryLog.fillCargo(Bytes.combine(
            new byte[]{(byte)(372 & 0xFF), (byte)(372 >> 8)},
            Bytes.toUint32(pumpTimeSec),
            Bytes.toUint32(sequenceNum),
            new byte[6],
            Bytes.firstTwoBytesLittleEndian(glucoseValue),
            Bytes.toUint32(timestamp)));
    }

    public int getGlucoseValue() {
        return glucoseValue;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
