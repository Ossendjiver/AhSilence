package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class PredictableFrequencyDiscoveryTest {
    @Test public void discoversPersistentHeadphoneToneUsingVehicleDetector() {
        PredictableFrequencyDiscovery d=new PredictableFrequencyDiscovery(15,600);
        final int sr=48000;
        for(int i=0;i<sr*8;i++){
            float x=(float)(0.08*Math.sin(2.0*Math.PI*120.0*i/sr));
            d.observe(x);
        }
        boolean found=false;for(double f:d.frequenciesHz())if(Math.abs(f-120.0)<1.5)found=true;
        assertTrue("persistent 120 Hz line should mature into predictable discovery",found);
    }

    @Test public void discoversVeryLowLevelToneWhenItIsProminentAboveLocalFloor() {
        PredictableFrequencyDiscovery d=new PredictableFrequencyDiscovery(15,600);
        final int sr=48000;long state=0x1234abcdL;
        for(int i=0;i<sr*10;i++){
            state=(1664525L*state+1013904223L)&0xffffffffL;
            double noise=(((state>>>8)&0xffff)/32768.0-1.0)*2.5e-6;
            float x=(float)(2.1e-5*Math.sin(2.0*Math.PI*38.0*i/sr)+noise);
            d.observe(x);
        }
        boolean found=false;for(double f:d.frequenciesHz())if(Math.abs(f-38.0)<1.5)found=true;
        assertTrue("~ -94 dBFS tone with strong local prominence should be discoverable",found);
    }
}
