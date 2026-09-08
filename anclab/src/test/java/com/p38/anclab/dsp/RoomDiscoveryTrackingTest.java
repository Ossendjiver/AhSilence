package com.p38.anclab.dsp;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoomDiscoveryTrackingTest {
    private static SpectrumAnalyzer.DetectedTone tone(double hz,double db){
        double amplitude=Math.pow(10.0,db/20.0);
        return new SpectrumAnalyzer.DetectedTone(hz,amplitude,db,db-14.0,14.0);
    }

    @Test public void roomRadiusKeepsModeratelyWanderingDominantModePersistent(){
        BroadbandDetector detector=new BroadbandDetector();
        detector.update(List.of(tone(131.4,-60)),0,2.5);
        detector.update(List.of(tone(132.7,-60)),550,2.5);
        List<BroadbandDetector.Candidate> ready=detector.update(List.of(tone(131.9,-60)),1100,2.5);
        assertFalse(ready.isEmpty());
        assertTrue(ready.get(0).frequencyHz()>130.0&&ready.get(0).frequencyHz()<134.0);
    }

    @Test public void defaultRadiusStillRejectsThatJumpForVehicleFallback(){
        BroadbandDetector detector=new BroadbandDetector();
        detector.update(List.of(tone(131.4,-60)),0);
        detector.update(List.of(tone(132.7,-60)),550);
        List<BroadbandDetector.Candidate> ready=detector.update(List.of(tone(131.9,-60)),1100);
        assertTrue(ready.isEmpty());
    }
}
