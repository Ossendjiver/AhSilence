package com.p38.anclab.dsp;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AutoControllerLatencyClosedLoopTest {
    @Test public void locksAndConfirmsSteady120HzAcrossMeasuredBluetoothDelay() {
        AutoController controller = new AutoController();
        controller.setOutputLatencyMs(430.0);
        controller.startTracking(0, 0.02, 120.0, "120 Hz", true);

        Complex disturbance = Complex.polar(0.030, 0.60);
        Complex secondaryPath = Complex.polar(2.0, 1.10);
        List<StampedCommand> history = new ArrayList<>();
        double bestImprovement = Double.NEGATIVE_INFINITY;

        for (long now = 0; now <= 14_000; now += 10) {
            history.add(new StampedCommand(now, controller.output().coefficient()));
            Complex audible = average(history, now - 730, now - 430);
            Complex residual = disturbance.add(secondaryPath.multiply(audible));
            if (now % 100 == 0) {
                controller.update(snapshot(residual), now);
                if (Double.isFinite(controller.currentImprovementDb()))
                    bestImprovement = Math.max(bestImprovement, controller.currentImprovementDb());
            }
        }

        assertEquals("RUNNING", controller.stageName());
        assertTrue(bestImprovement > 6.0);
    }

    private static Complex average(List<StampedCommand> history, long from, long to) {
        Complex sum = Complex.ZERO;
        int count = 0;
        for (StampedCommand sample : history) {
            if (sample.atMs >= from && sample.atMs <= to) {
                sum = sum.add(sample.command);
                count++;
            }
        }
        return count == 0 ? Complex.ZERO : sum.multiply(1.0 / count);
    }

    private static SpectrumSnapshot snapshot(Complex target) {
        return new SpectrumSnapshot(120.0, target.magnitude(), -30.0, 12.0,
                120.0, target, SpectrumAnalyzer.linearToDb(target.magnitude()), -30.0, 2.0);
    }

    private record StampedCommand(long atMs, Complex command) { }
}
