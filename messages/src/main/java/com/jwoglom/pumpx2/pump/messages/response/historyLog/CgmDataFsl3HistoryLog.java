package com.jwoglom.pumpx2.pump.messages.response.historyLog;

import org.apache.commons.lang3.Validate;
import com.jwoglom.pumpx2.pump.messages.annotations.HistoryLogProps;
import com.jwoglom.pumpx2.pump.messages.helpers.Bytes;

@HistoryLogProps(
    opCode = 480,
    displayName = "CGM Data (FSL3)",
    internalName = "LID_CGM_DATA_FSL3"
)
public class CgmDataFsl3HistoryLog extends HistoryLog {
    
    private int glucoseValue;
    private long timestamp;

    public CgmDataFsl3HistoryLog() {}
    public CgmDataFsl3HistoryLog(long pumpTimeSec, long sequenceNum, int glucoseValue, long timestamp) {
        super(pumpTimeSec, sequenceNum);
        this.cargo = buildCargo(pumpTimeSec, sequenceNum, glucoseValue, timestamp);
        this.glucoseValue = glucoseValue;
        this.timestamp = timestamp;
    }

    public int typeId() {
        return 480;
    }

    public void parse(byte[] raw) {
        Validate.isTrue(raw.length == 26);
        this.cargo = raw;
        parseBase(raw);
        // Tandem typically stores the glucose value at offset 16 and the sensor timestamp at offset 18
        this.glucoseValue = Bytes.readShort(raw, 16);
        this.timestamp = Bytes.readUint32(raw, 18);
    }

    public static byte[] buildCargo(long pumpTimeSec, long sequenceNum, int glucoseValue, long timestamp) {
        return HistoryLog.fillCargo(Bytes.combine(
            new byte[]{(byte)(480 & 0xFF), (byte)(480 >> 8)},
            Bytes.toUint32(pumpTimeSec),
            Bytes.toUint32(sequenceNum),
            new byte[6], // Padding
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
