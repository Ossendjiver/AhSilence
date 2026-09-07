package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class AutoControllerSecondaryPathRecipeTest {
    private static final double FREQUENCY_HZ = 34.5;

    @Test public void learnedPathCalculatesFreshPhaseFromTheCurrentDisturbance() {
        Complex secondaryPath = Complex.polar(2.0, 0.65);
        Complex firstDisturbance = Complex.polar(0.04, 0.25);
        Complex secondDisturbance = Complex.polar(0.04, 1.10);

        AutoController first = runWarmStart(secondaryPath, firstDisturbance);
        AutoController second = runWarmStart(secondaryPath, secondDisturbance);

        assertEquals("RUNNING", first.stageName());
        assertEquals("RUNNING", second.stageName());
        assertNotEquals(first.output().phaseRadians(), second.output().phaseRadians(), 0.20);
    }

    @Test public void learnedPathIsVerifiedAtHalfStrengthBeforeRunning() {
        Complex secondaryPath = Complex.polar(2.0, -0.40);
        Complex disturbance = Complex.polar(0.04, 0.75);
        AutoController controller = new AutoController();
        controller.startTrackingWithSecondaryPath(0, 0.05, FREQUENCY_HZ,
                "saved path", true, secondaryPath);

        controller.update(snapshot(disturbance), 700);
        Complex optimum = disturbance.negate().divide(secondaryPath);
        assertEquals("VERIFY_HALF", controller.stageName());
        assertComplex(optimum.multiply(0.5), controller.output().coefficient());

        controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))), 1400);
        assertEquals("VERIFY_FULL", controller.stageName());
        assertComplex(optimum, controller.output().coefficient());

        controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))), 2100);
        assertEquals("RUNNING", controller.stageName());
    }

    private static AutoController runWarmStart(Complex secondaryPath, Complex disturbance) {
        AutoController controller = new AutoController();
        controller.startTrackingWithSecondaryPath(0, 0.05, FREQUENCY_HZ,
                "saved path", true, secondaryPath);
        controller.update(snapshot(disturbance), 700);
        controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))), 1400);
        controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))), 2100);
        return controller;
    }

    private static SpectrumSnapshot snapshot(Complex target) {
        return new SpectrumSnapshot(FREQUENCY_HZ, target.magnitude(), -30, 10,
                FREQUENCY_HZ, target, -30, -45, 2.0);
    }

    private static void assertComplex(Complex expected, Complex actual) {
        assertEquals(expected.re(), actual.re(), 1.0e-9);
        assertEquals(expected.im(), actual.im(), 1.0e-9);
    }
}
