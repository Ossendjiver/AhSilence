package com.p38.anclab.dsp;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class BroadbandDetectorRelativeFloorTest {
    @Test public void lowAbsoluteLevelCanMatureWhenLocallyProminent(){
        BroadbandDetector d=new BroadbandDetector();
        SpectrumAnalyzer.DetectedTone t=new SpectrumAnalyzer.DetectedTone(38.0,2.0e-5,-94.0,-116.0,22.0);
        assertTrue(d.update(List.of(t),0).isEmpty());
        assertTrue(d.update(List.of(t),600).isEmpty());
        List<BroadbandDetector.Candidate> ready=d.update(List.of(t),1200);
        assertEquals(1,ready.size());
        assertEquals(38.0,ready.get(0).frequencyHz(),0.4);
        // Track statistics are intentionally smoothed from conservative initial values.
        assertTrue(ready.get(0).prominenceDb()>=8.0);
    }

    @Test public void louderButUnremarkableBumpIsRejected(){
        BroadbandDetector d=new BroadbandDetector();
        SpectrumAnalyzer.DetectedTone t=new SpectrumAnalyzer.DetectedTone(80.0,3.0e-4,-70.0,-74.0,4.0);
        for(int i=0;i<6;i++)assertTrue(d.update(List.of(t),i*600L).isEmpty());
    }
}
