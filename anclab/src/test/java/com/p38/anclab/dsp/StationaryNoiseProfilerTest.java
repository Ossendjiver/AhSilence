package com.p38.anclab.dsp;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.assertTrue;

public final class StationaryNoiseProfilerTest {
    @Test public void reportsStableLowFrequencyBackgroundSeparatelyFromRawNoise(){
        StationaryNoiseProfiler p=new StationaryNoiseProfiler();Random r=new Random(9);int sr=48000;
        for(int i=0;i<sr*7;i++){
            double low=2.0e-5*Math.sin(2*Math.PI*38*i/sr);
            double high=1.7e-4*Math.sin(2*Math.PI*10000*i/sr);
            p.observe((float)(low+high+2e-6*r.nextGaussian()));
        }
        StationaryNoiseProfiler.Snapshot s=p.snapshot();
        assertTrue(s.stationary());
        assertTrue("ANC-band metric should reject most high-frequency diagnostic noise",s.ancBandRmsDbFs()<s.rawRmsDbFs()-8.0);
        assertTrue(s.variabilityDb()<0.75);
    }
}
