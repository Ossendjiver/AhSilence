package com.p38.anclab.dsp;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RoomNoBlindProbeTest {
    private static SpectrumSnapshot snapshot(double magnitude) {
        Complex c=new Complex(magnitude,0.0);
        return new SpectrumSnapshot(50.0,c.magnitude(),-35,12,50.0,c,-35,-30,2.0);
    }

    @Test public void roomWithoutCalibratedPathNeverStartsBlindProbe() {
        AutoController c=new AutoController();
        c.setDirectErrorLearning(true);
        c.startTracking(1000,0.02,50.0,"Room",false);
        c.update(snapshot(1.0),1500);
        assertEquals("IDLE",c.stageName());
        assertTrue(c.activeVerificationFailed());
        assertEquals(0.0,c.output().gain(),1e-12);
    }

    @Test public void failedCalibratedPathGetsOnlyTinyRefinementThenQuarantines() {
        AutoController c=new AutoController();
        c.setDirectErrorLearning(true);
        c.setBlindProbesAllowed(false);
        c.startTrackingWithSecondaryPath(1000,0.02,50.0,"Room",false,new Complex(1.0,0.0));
        c.update(snapshot(1.0),1500);
        c.update(snapshot(1.20),2000);
        assertEquals("REFINE_POSITIVE",c.stageName());
        assertTrue(c.output().gain()<=0.0015+1e-12);
        // No measurable +/- acoustic response: refinement must stop rather than escalating to
        // the old blind secondary-path probe.
        c.update(snapshot(1.0),2500);
        assertEquals("REFINE_NEGATIVE",c.stageName());
        assertTrue(c.output().gain()<=0.0015+1e-12);
        c.update(snapshot(1.0),3000);
        assertEquals("IDLE",c.stageName());
        assertTrue(c.activeVerificationFailed());
        assertEquals(0.0,c.output().gain(),1e-12);
    }
}
