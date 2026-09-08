package com.p38.anclab.dsp;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SharedCalibratedSafetyTest {
    private static SpectrumSnapshot snapshot(double magnitude) {
        Complex c=new Complex(magnitude,0.0);
        return new SpectrumSnapshot(68.0,c.magnitude(),-35,12,68.0,c,-35,-30,2.0);
    }

    private static SpectrumAnalyzer.DetectedTone tone(double hz,double db){
        double amplitude=Math.pow(10.0,db/20.0);
        return new SpectrumAnalyzer.DetectedTone(hz,amplitude,db,db-14.0,14.0);
    }

    @Test public void vehicleControllerCanForbidBlindProbeWithoutRoomMode(){
        AutoController c=new AutoController();
        c.setDirectErrorLearning(false);
        c.setBlindProbesAllowed(false);
        c.startTracking(1000,0.02,68.0,"E46 fallback",false);
        c.update(snapshot(1.0),1800);
        assertEquals("IDLE",c.stageName());
        assertTrue(c.activeVerificationFailed());
        assertEquals(0.0,c.output().gain(),1e-12);
    }

    @Test public void vehicleBadCalibratedSeedGetsOnlyTinyRefinementThenQuarantines(){
        AutoController c=new AutoController();
        c.setDirectErrorLearning(false);
        c.setBlindProbesAllowed(false);
        c.startTrackingWithSecondaryPath(1000,0.02,68.0,"E46 telemetry",true,new Complex(1.0,0.0));
        c.update(snapshot(1.0),1800);
        assertEquals("REFINE_POSITIVE",c.stageName());
        assertTrue(c.output().gain()<=0.0015+1e-12);
        // If the tiny symmetric measurement cannot resolve a local transfer, fail closed.  This
        // verifies that vehicle mode does not fall back to the former large blind reprobe.
        c.update(snapshot(1.0),2600);
        assertEquals("REFINE_NEGATIVE",c.stageName());
        assertTrue(c.output().gain()<=0.0015+1e-12);
        c.update(snapshot(1.0),3400);
        assertEquals("IDLE",c.stageName());
        assertTrue(c.activeVerificationFailed());
        assertEquals(0.0,c.output().gain(),1e-12);
    }

    @Test public void calibratedFallbackRadiusKeepsModerateVehicleToneWanderPersistent(){
        BroadbandDetector detector=new BroadbandDetector();
        detector.update(List.of(tone(131.4,-60)),0,2.5);
        detector.update(List.of(tone(133.1,-60)),550,2.5);
        List<BroadbandDetector.Candidate> ready=detector.update(List.of(tone(132.2,-60)),1100,2.5);
        assertFalse(ready.isEmpty());
        assertTrue(ready.get(0).frequencyHz()>130.0&&ready.get(0).frequencyHz()<134.5);
    }
}
