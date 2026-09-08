package com.p38.anclab.dsp;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CalibratedMicroRefinementTest {
    private static SpectrumSnapshot snapshot(AutoController c, Complex disturbance, Complex truePath) {
        Complex residual=disturbance.add(truePath.multiply(c.output().coefficient()));
        return new SpectrumSnapshot(68.0,residual.magnitude(),-35,12,68.0,residual,-35,-30,2.0);
    }

    @Test public void wrongCalibratedPhaseIsMeasuredLocallyBeforeAnyLargeTrial() {
        AutoController c=new AutoController();
        c.setDirectErrorLearning(true);
        c.setBlindProbesAllowed(false);
        Complex disturbance=new Complex(0.10,0.0);
        Complex truePath=new Complex(1.0,0.0);
        // Deliberately 90 degrees wrong.  The stored path may choose the tiny dither phase/scale,
        // but must never be trusted for a large first cancellation command.
        c.startTrackingWithSecondaryPath(0,0.02,68.0,"Room",false,new Complex(0.0,1.0));
        c.update(snapshot(c,disturbance,truePath),900);
        assertEquals("REFINE_POSITIVE",c.stageName());
        assertTrue(c.output().gain()<=0.0015+1e-12);
        c.update(snapshot(c,disturbance,truePath),1800);
        assertEquals("REFINE_NEGATIVE",c.stageName());
        assertTrue(c.output().gain()<=0.0015+1e-12);
        c.update(snapshot(c,disturbance,truePath),2700);
        assertEquals("VERIFY_HALF",c.stageName());
        assertTrue(c.output().gain()<=0.0040+1e-12);
        c.update(snapshot(c,disturbance,truePath),3600);
        assertEquals("VERIFY_FULL",c.stageName());
        c.update(snapshot(c,disturbance,truePath),4500);
        assertEquals("AUDIT_OFF",c.stageName());
        c.update(snapshot(c,disturbance,truePath),5400);
        c.update(snapshot(c,disturbance,truePath),6300);
        assertEquals("RUNNING",c.stageName());
        assertTrue(c.hasPassedActiveVerification());
        assertTrue(c.currentImprovementDb()>1.0);
    }

    @Test public void unmeasurableLocalPathStillQuarantinesWithoutEscalating() {
        AutoController c=new AutoController();
        c.setDirectErrorLearning(true);
        c.setBlindProbesAllowed(false);
        Complex disturbance=new Complex(0.10,0.0);
        Complex noAcousticResponse=Complex.ZERO;
        c.startTrackingWithSecondaryPath(0,0.02,68.0,"Room",false,new Complex(0.0,1.0));
        c.update(snapshot(c,disturbance,noAcousticResponse),900);
        assertEquals("REFINE_POSITIVE",c.stageName());
        assertTrue(c.output().gain()<=0.0015+1e-12);
        c.update(snapshot(c,disturbance,noAcousticResponse),1800);
        assertEquals("REFINE_NEGATIVE",c.stageName());
        c.update(snapshot(c,disturbance,noAcousticResponse),2700);
        assertEquals("IDLE",c.stageName());
        assertTrue(c.activeVerificationFailed());
        assertEquals(0.0,c.output().gain(),1e-12);
    }
}
