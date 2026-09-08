package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class PredictableFrequencyDiscoveryTest {
    @Test public void discoversPersistentHeadphoneToneUsingVehicleDetector() {
        PredictableFrequencyDiscovery d=new PredictableFrequencyDiscovery(15,600);
        final int sr=48000;long ms=0;
        for(int i=0;i<sr*8;i++){
            float x=(float)(0.08*Math.sin(2.0*Math.PI*120.0*i/sr));
            ms=Math.round(i*1000.0/sr);d.observe(x,ms);
        }
        boolean found=false;for(double f:d.frequenciesHz())if(Math.abs(f-120.0)<1.5)found=true;
        assertTrue("persistent 120 Hz line should mature into predictable discovery",found);
    }
}
