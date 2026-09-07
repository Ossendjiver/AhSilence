package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AutoControllerRetuneTest {
    @Test public void smallTrackerMotionDoesNotRestartInFlightCalibration() {
        AutoController controller = new AutoController();
        controller.startTracking(0, 0.02, 34.5, "Auto 34.5 Hz", false);

        controller.followFrequency(34.70, 100);

        assertEquals("BASELINE", controller.stageName());
        assertEquals(34.5, controller.output().frequencyHz(), 1.0e-9);
    }

    @Test public void materialTrackerMotionRetunesCalibrationCentre() {
        AutoController controller = new AutoController();
        controller.startTracking(0, 0.02, 34.5, "Auto 34.5 Hz", false);

        controller.followFrequency(35.10, 100);

        assertEquals("BASELINE", controller.stageName());
        assertEquals(35.1, controller.output().frequencyHz(), 1.0e-9);
    }
}
