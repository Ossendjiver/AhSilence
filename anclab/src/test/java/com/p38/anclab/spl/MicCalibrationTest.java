package com.p38.anclab.spl;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MicCalibrationTest {
    @Test public void parsesSensitivitySerialAndInterpolates() throws Exception {
        String data = "Sens Factor = -20.0dB, SERNO: 7001234\n20 -4.0\n40 0.0\n80 2.0\n";
        MicCalibration calibration = MicCalibration.parse(
                new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), "umik.txt");
        assertTrue(calibration.hasSensitivity());
        assertEquals(114.0, calibration.provisionalSplOffsetDb(), 1.0e-9);
        assertEquals(-2.0, calibration.correctionAt(30), 1.0e-9);
        assertTrue(calibration.description().contains("7001234"));
    }
}
