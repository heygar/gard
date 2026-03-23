package com.jwoglom.pumpx2.pump.messages.request.currentStatus;

import static com.jwoglom.pumpx2.pump.messages.MessageTester.assertHexEquals;

import com.jwoglom.pumpx2.pump.messages.MessageTester;
import com.jwoglom.pumpx2.pump.messages.bluetooth.CharacteristicUUID;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CommonSoftwareInfoRequest;

import org.apache.commons.codec.DecoderException;
import org.junit.Ignore;
import org.junit.Test;

public class CommonSoftwareInfoRequestTest {
    @Test
    public void testCommonSoftwareInfoRequest() throws DecoderException {
        CommonSoftwareInfoRequest expected = new CommonSoftwareInfoRequest(new byte[]{0});

        CommonSoftwareInfoRequest parsedReq = (CommonSoftwareInfoRequest) MessageTester.test(
                "002f8e2f01006462",
                47,
                1,
                CharacteristicUUID.CURRENT_STATUS_CHARACTERISTICS,
                expected
        );

        assertHexEquals(expected.getCargo(), parsedReq.getCargo());
    }
}