package com.p38.anclab.spl;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MicCalibrationTest {
    @Test public void parsesUmikSensitivityAndFrequencyResponse() throws Exception {
        String text="Sens Factor = -18.00 dBFS\nSERNO: 123456\n20 1.0\n200 -1.0\n";
        MicCalibration parsed=MicCalibration.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),"UMIK-1");
        assertTrue(Double.isNaN(parsed.provisionalSplOffsetDb()));
        assertFalse(parsed.hasSplOffset());
        assertTrue(parsed.hasFrequencyResponse());
        assertEquals(0.0,parsed.correctionAt(110),1e-9);
        assertTrue(parsed.description().contains("123456"));
    }

    @Test public void benchmarkedMicStoresAbsoluteOffset() {
        MicCalibration calibration=MicCalibration.benchmarked("Phone mic",108.5);
        assertEquals(108.5,calibration.provisionalSplOffsetDb(),1e-9);
        assertEquals(0.0,calibration.correctionAt(100),1e-9);
    }
}
