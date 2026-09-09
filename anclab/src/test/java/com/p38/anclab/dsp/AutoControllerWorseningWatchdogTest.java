package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AutoControllerWorseningWatchdogTest {
    @Test public void sustainedLouderResidualMutesThenReacquiresAtHalfStrength() {
        AutoController controller = new AutoController();
        controller.setOutputLatencyMs(0);
        controller.startTrackingWithSecondaryPath(0, 0.02, 120.0, "120 Hz", true,
                Complex.polar(2.0, 0.4));

        Complex baseline = Complex.polar(0.03, 0.7);
        Complex path=Complex.polar(2.0,0.4);
        for(long now=800;now<=10_400&&!"RUNNING".equals(controller.stageName());now+=800)
            controller.update(snapshot(baseline.add(path.multiply(controller.output().coefficient()))),now);
        assertEquals("RUNNING", controller.stageName());

        Complex louder = baseline.multiply(1.20);
        controller.update(snapshot(louder), 10_500);
        assertEquals("REACQUIRE_BASELINE", controller.stageName());
        assertEquals(0.0, controller.output().gain(), 1.0e-12);
        assertEquals(1, controller.mutedReacquisitions());

        controller.update(snapshot(baseline), 11_300);
        assertEquals("VERIFY_HALF", controller.stageName());
        assertTrue(controller.output().gain() > 0.0);
    }

    private static SpectrumSnapshot snapshot(Complex target) {
        return new SpectrumSnapshot(120.0, target.magnitude(), -30.0, 12.0,
                120.0, target, SpectrumAnalyzer.linearToDb(target.magnitude()), -30.0, 2.0);
    }
}
