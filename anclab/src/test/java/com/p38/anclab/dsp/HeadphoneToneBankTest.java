package com.p38.anclab.dsp;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

public final class HeadphoneToneBankTest {
    @Test public void admitsPersistentLowLevel38HzTone(){
        HeadphoneToneBank bank=new HeadphoneToneBank(0.12f,0.50f);long state=0x4321L;
        for(int i=0;i<48000*9;i++){
            state=(1664525L*state+1013904223L)&0xffffffffL;
            double noise=(((state>>>8)&0xffff)/32768.0-1.0)*2.0e-6;
            float x=(float)(2.1e-5*Math.sin(2*Math.PI*38.0*i/48000.0)+noise);
            bank.process(x);
        }
        boolean found=false;for(double f:bank.frequenciesHz())if(Math.abs(f-38.0)<1.5)found=true;
        assertTrue("38 Hz low-level persistent tone should own a headphone tone lane",found);
    }
}
