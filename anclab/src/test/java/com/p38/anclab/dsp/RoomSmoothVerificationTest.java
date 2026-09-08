package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RoomSmoothVerificationTest {
    private static final double HZ = 56.0;

    @Test public void routineVerificationDoesNotHardMuteEveryFewSeconds() {
        AutoController controller = verifiedController();
        controller.update(snapshot(0.05, 0.0), 8_000);
        assertEquals("RUNNING", controller.stageName());
        assertTrue(controller.output().gain() > 0.0);
    }

    @Test public void weakVirtualBenefitPersistsAcrossFineCyclesAndTriggersSoftAudit() {
        AutoController controller = verifiedController();
        long now = 3_500;
        double fullGainBeforeAudit = controller.output().gain();

        // Normal Room operation alternates RUNNING with settled VERIFY_FINE cycles. Feed a
        // deliberately zero-benefit residual for long enough to prove that the effectiveness
        // accumulator survives those adaptation cycles instead of being reset by them.
        for (int i = 0; i < 80 && !"AUDIT_OFF".equals(controller.stageName()); i++) {
            Complex command = controller.output().coefficient();
            Complex zeroBenefitResidual = command.multiply(0.5);
            fullGainBeforeAudit = controller.output().gain();
            now += 500;
            controller.update(snapshot(zeroBenefitResidual.re(), zeroBenefitResidual.im()), now);
        }

        assertEquals("AUDIT_OFF", controller.stageName());
        assertTrue("soft audit must retain some ANC instead of hard muting",
                controller.output().gain() > 0.0);
        assertTrue("soft audit must reduce the command while measuring benefit",
                controller.output().gain() < fullGainBeforeAudit);
    }

    private static AutoController verifiedController() {
        AutoController controller = new AutoController();
        controller.setDirectErrorLearning(true);
        controller.startTrackingWithSecondaryPath(1_000, 0.02, HZ,
                "Room", false, new Complex(1.0, 0.0));
        controller.update(snapshot(1.0, 0.0), 1_500);
        controller.update(snapshot(0.45, 0.0), 2_000);
        controller.update(snapshot(0.35, 0.0), 2_500);
        controller.update(snapshot(0.80, 0.0), 3_000);
        controller.update(snapshot(0.45, 0.0), 3_500);
        assertEquals("RUNNING", controller.stageName());
        return controller;
    }

    private static SpectrumSnapshot snapshot(double real, double imag) {
        Complex target = new Complex(real, imag);
        return new SpectrumSnapshot(HZ, target.magnitude(), -35, 12,
                HZ, target, -35, -30, 2.0);
    }
}
