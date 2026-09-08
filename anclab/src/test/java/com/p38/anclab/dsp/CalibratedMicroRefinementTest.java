package com.p38.anclab.dsp;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CalibratedMicroRefinementTest {
    private static SpectrumSnapshot snapshot(AutoController c, Complex disturbance, Complex truePath) {
        Complex residual=disturbance.add(truePath.multiply(c.output().coefficient()));
        return new SpectrumSnapshot(68.0,residual.magnitude(),-35,12,68.0,residual,-35,-30,2.0);
    }

    @Test public void wrongCalibratedPhaseGetsOneTinyLocalRefinementAndCanVerify() {
        AutoController c=new AutoController();
        c.setDirectErrorLearning(true);
        c.setBlindProbesAllowed(false);
        Complex disturbance=new Complex(0.10,0.0);
        Complex truePath=new Complex(1.0,0.0);
        // 90-degree seed error: safe but incapable of cancellation until locally refined.
        c.startTrackingWithSecondaryPath(0,0.02,68.0,"Room",false,new Complex(0.0,1.0));
        c.update(snapshot(c,disturbance,truePath),500);       // baseline -> half seed
        c.update(snapshot(c,disturbance,truePath),1000);      // half seed -> full seed
        c.update(snapshot(c,disturbance,truePath),1500);      // full seed fails -> refine +
        assertEquals("REFINE_POSITIVE",c.stageName());
        assertTrue(c.output().gain()<=0.0015+1e-12);
        c.update(snapshot(c,disturbance,truePath),2000);      // refine + -> refine -
        assertEquals("REFINE_NEGATIVE",c.stageName());
        assertTrue(c.output().gain()<=0.0015+1e-12);
        c.update(snapshot(c,disturbance,truePath),2500);      // measured H -> verified half
        assertEquals("VERIFY_HALF",c.stageName());
        c.update(snapshot(c,disturbance,truePath),3000);      // half -> full
        c.update(snapshot(c,disturbance,truePath),3500);      // full -> physical A/B off
        assertEquals("AUDIT_OFF",c.stageName());
        c.update(snapshot(c,disturbance,truePath),4000);      // off -> on
        c.update(snapshot(c,disturbance,truePath),4500);      // on -> running
        assertEquals("RUNNING",c.stageName());
        assertTrue(c.hasPassedActiveVerification());
        assertTrue(c.currentImprovementDb()>1.0);
    }

    @Test public void unmeasurableMicroRefinementStillQuarantines() {
        AutoController c=new AutoController();
        c.setDirectErrorLearning(true);
        c.setBlindProbesAllowed(false);
        Complex disturbance=new Complex(0.10,0.0);
        Complex noAcousticResponse=Complex.ZERO;
        c.startTrackingWithSecondaryPath(0,0.02,68.0,"Room",false,new Complex(0.0,1.0));
        c.update(snapshot(c,disturbance,noAcousticResponse),500);
        c.update(snapshot(c,disturbance,noAcousticResponse),1000);
        c.update(snapshot(c,disturbance,noAcousticResponse),1500);
        c.update(snapshot(c,disturbance,noAcousticResponse),2000);
        c.update(snapshot(c,disturbance,noAcousticResponse),2500);
        assertEquals("IDLE",c.stageName());
        assertTrue(c.activeVerificationFailed());
        assertEquals(0.0,c.output().gain(),1e-12);
    }
}
