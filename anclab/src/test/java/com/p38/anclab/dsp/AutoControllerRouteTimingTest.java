package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AutoControllerRouteTimingTest {
    @Test public void waitsForTransportAndACompleteFreshObservationWindow() {
        AutoController controller = new AutoController();
        controller.setOutputLatencyMs(430.0);
        controller.startTracking(0, 0.02, 120.0, "120 Hz", true);

        assertEquals(830L, controller.observationSettleMs());
        controller.update(snapshot(), 829L);
        assertEquals("BASELINE", controller.stageName());
        controller.update(snapshot(), 830L);
        assertEquals("PROBE_POSITIVE", controller.stageName());
    }

    @Test public void evenDirectRoutesWaitForAFullyFreshAnalyzerWindow() {
        AutoController controller = new AutoController();
        assertEquals(650L, controller.observationSettleMs());
    }

    private static SpectrumSnapshot snapshot() {
        Complex target = Complex.polar(0.04, 0.35);
        return new SpectrumSnapshot(120.0, target.magnitude(), -28.0, 12.0,
                120.0, target, -28.0, -35.0, 2.0);
    }
}
