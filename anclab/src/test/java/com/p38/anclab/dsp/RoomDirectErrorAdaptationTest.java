package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RoomDirectErrorAdaptationTest {
    private static final double FREQUENCY_HZ = 40.0;

    @Test public void directErrorModeUsesShorterSettledVerificationWindow() {
        Complex secondaryPath = Complex.polar(2.0, -0.35);
        Complex disturbance = Complex.polar(0.04, 0.70);
        AutoController controller = new AutoController();
        controller.setDirectErrorLearning(true);
        controller.startTrackingWithSecondaryPath(0, 0.05, FREQUENCY_HZ,
                "room tone", false, secondaryPath);

        controller.update(snapshot(disturbance), 419);
        assertEquals("BASELINE", controller.stageName());

        controller.update(snapshot(disturbance), 420);
        assertEquals("VERIFY_HALF", controller.stageName());
        Complex halfResidual = disturbance.add(secondaryPath.multiply(controller.output().coefficient()));

        controller.update(snapshot(halfResidual), 839);
        assertEquals("VERIFY_HALF", controller.stageName());
        controller.update(snapshot(halfResidual), 840);
        assertEquals("VERIFY_FULL", controller.stageName());

        Complex fullResidual = disturbance.add(secondaryPath.multiply(controller.output().coefficient()));
        controller.update(snapshot(fullResidual), 1_260);
        assertEquals("AUDIT_OFF", controller.stageName());
    }

    @Test public void directErrorModeBeginsFineAdaptationAfter220MsFollowingAudit() {
        Complex secondaryPath = Complex.polar(2.0, -0.35);
        Complex disturbance = Complex.polar(0.04, 0.70);
        AutoController controller = runDirectWarmStart(secondaryPath, disturbance);
        Complex changedResidual = Complex.polar(0.02, 1.05);

        controller.update(snapshot(changedResidual), 2_319);
        assertEquals("RUNNING", controller.stageName());
        controller.update(snapshot(changedResidual), 2_320);
        assertEquals("VERIFY_FINE", controller.stageName());
    }

    private static AutoController runDirectWarmStart(Complex secondaryPath, Complex disturbance) {
        AutoController controller = new AutoController();
        controller.setDirectErrorLearning(true);
        controller.startTrackingWithSecondaryPath(0, 0.05, FREQUENCY_HZ,
                "room tone", false, secondaryPath);
        controller.update(snapshot(disturbance), 420);
        controller.update(snapshot(disturbance.add(secondaryPath.multiply(controller.output().coefficient()))), 840);
        Complex controlledResidual = disturbance.add(secondaryPath.multiply(controller.output().coefficient()));
        controller.update(snapshot(controlledResidual), 1_260);
        assertEquals("AUDIT_OFF", controller.stageName());
        controller.update(snapshot(disturbance), 1_680);
        assertEquals("AUDIT_ON", controller.stageName());
        controlledResidual = disturbance.add(secondaryPath.multiply(controller.output().coefficient()));
        controller.update(snapshot(controlledResidual), 2_100);
        assertEquals("RUNNING", controller.stageName());
        return controller;
    }

    private static SpectrumSnapshot snapshot(Complex target) {
        return new SpectrumSnapshot(FREQUENCY_HZ, target.magnitude(), -30, 10,
                FREQUENCY_HZ, target, -30, -45, 2.0);
    }
}
