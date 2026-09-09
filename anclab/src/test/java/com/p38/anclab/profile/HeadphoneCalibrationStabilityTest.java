package com.p38.anclab.profile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HeadphoneCalibrationStabilityTest {
    @Test public void computesRepeatDelayDifferenceAndConservativeSettleDelay() {
        HeadphoneCalibration calibration = new HeadphoneCalibration();
        calibration.sampleRateHz = 48_000;
        calibration.delaySamples = 20_000;
        calibration.delayCheckOneSamples = 20_000;
        calibration.delayCheckTwoSamples = 20_480;

        assertTrue(calibration.hasDelayStabilityMeasurement());
        assertEquals(10.0f, calibration.delayDifferenceMs(), 0.001f);
        assertEquals(426.666f, calibration.conservativeDelayMs(), 0.01f);
        assertFalse(calibration.latencyIsStable());
    }

    @Test public void legacyCalibrationRemainsUsableButHasNoStabilityMeasurement() {
        HeadphoneCalibration calibration = new HeadphoneCalibration();
        calibration.delaySamples = 1_440;
        assertFalse(calibration.hasDelayStabilityMeasurement());
        assertTrue(calibration.latencyIsStable());
        assertEquals(30.0f, calibration.conservativeDelayMs(), 0.001f);
    }
}
