package com.p38.anclab.dsp;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BroadbandDetectorRelativeFloorTest {
    @Test public void quietToneCanMatureWhenLocallyProminentAndAboveSafetyFloor() {
        BroadbandDetector detector=new BroadbandDetector();
        SpectrumAnalyzer.DetectedTone tone=new SpectrumAnalyzer.DetectedTone(
                120.0,1.0e-4,-80.0,-102.0,22.0);
        assertTrue(detector.update(List.of(tone),0).isEmpty());
        assertTrue(detector.update(List.of(tone),600).isEmpty());
        List<BroadbandDetector.Candidate> ready=detector.update(List.of(tone),1200);
        assertEquals(1,ready.size());
        assertEquals(120.0,ready.get(0).frequencyHz(),0.4);
        assertTrue(ready.get(0).prominenceDb()>=10.0);
    }

    @Test public void louderButUnremarkableBumpIsRejected() {
        BroadbandDetector detector=new BroadbandDetector();
        SpectrumAnalyzer.DetectedTone tone=new SpectrumAnalyzer.DetectedTone(
                80.0,3.0e-4,-70.0,-74.0,4.0);
        for(int i=0;i<6;i++)assertTrue(detector.update(List.of(tone),i*600L).isEmpty());
    }

    @Test public void extremelyQuietPeakRemainsRejectedDespiteProminence() {
        BroadbandDetector detector=new BroadbandDetector();
        SpectrumAnalyzer.DetectedTone tone=new SpectrumAnalyzer.DetectedTone(
                90.0,2.0e-6,-114.0,-140.0,26.0);
        for(int i=0;i<6;i++)assertTrue(detector.update(List.of(tone),i*600L).isEmpty());
    }
}
