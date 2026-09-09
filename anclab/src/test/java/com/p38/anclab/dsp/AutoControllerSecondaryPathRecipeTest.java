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

        controller.update(snapshot(disturbance), 850);
        Complex optimum = disturbance.negate().divide(secondaryPath);
        assertEquals("VERIFY_HALF", controller.stageName());
        assertComplex(optimum.multiply(0.5), controller.output().coefficient());

        controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))), 1700);
        assertEquals("VERIFY_CONFIRM_BASELINE", controller.stageName());
        assertComplex(Complex.ZERO, controller.output().coefficient());

        controller.update(snapshot(disturbance), 2550);
        assertEquals("VERIFY_CONFIRM_OUTPUT", controller.stageName());
        controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))), 3400);
        assertEquals("VERIFY_CONFIRM_BASELINE", controller.stageName());
        controller.update(snapshot(disturbance), 4250);
        controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))), 5100);
        assertEquals("VERIFY_FULL", controller.stageName());
        assertComplex(optimum, controller.output().coefficient());

        controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))), 5950);
        controller.update(snapshot(disturbance), 6800);
        controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))), 7650);
        controller.update(snapshot(disturbance), 8500);
        controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))), 9350);
        assertEquals("RUNNING", controller.stageName());
    }

    @Test public void repeatedFineRejectionsReusePathInsteadOfRefreshingBaseline() {
        Complex secondaryPath = Complex.polar(2.0, -0.40);
        Complex disturbance = Complex.polar(0.04, 0.75);
        AutoController controller = runWarmStart(secondaryPath, disturbance);
        long now = 10_200;

        for (int attempt = 0; attempt < 3; attempt++) {
            controller.update(snapshot(Complex.polar(0.02, 1.4)), now);
            assertEquals("VERIFY_FINE", controller.stageName());
            now += 850;
            controller.update(snapshot(Complex.polar(0.04, -1.0)), now);
            now += 850;
        }

        assertEquals("VERIFY_HALF", controller.stageName());
    }

    private static AutoController runWarmStart(Complex secondaryPath, Complex disturbance) {
        AutoController controller = new AutoController();
        controller.startTrackingWithSecondaryPath(0, 0.05, FREQUENCY_HZ,
                "saved path", true, secondaryPath);
        for(long now=850;now<=10_200&&!"RUNNING".equals(controller.stageName());now+=850)
            controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))),now);
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
