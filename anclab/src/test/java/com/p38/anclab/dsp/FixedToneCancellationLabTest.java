package com.p38.anclab.dsp;

import org.junit.Test;
import static org.junit.Assert.*;

public class FixedToneCancellationLabTest {
    @Test public void bruteForcePhysicalSearchFindsDelayed120HzCancellation(){
        FixedToneCancellationLab lab=new FixedToneCancellationLab();
        lab.setUserOutputScale(1f);
        final int sr=48000,delay=37,total=sr*35;
        float[] ring=new float[512];int p=0;double before=0,after=0;int bn=0,an=0;double max=0;
        for(int n=0;n<total;n++){
            double d=0.0015*Math.cos(2*Math.PI*120.0*n/sr+0.7);
            double secondary=0.22*ring[(p-delay+ring.length)%ring.length];
            float mic=(float)(d+secondary);
            float out=lab.process(mic);ring[p]=out;if(++p==ring.length)p=0;max=Math.max(max,Math.abs(out));
            if(n<sr*2){before+=mic*mic;bn++;}
            if(n>sr*31){after+=mic*mic;an++;}
        }
        assertTrue("lab must never exceed its digital cap",max<=0.02001);
        assertTrue("physical search should identify a useful solution",lab.bestImprovementDb()>1.0);
        assertTrue("final held residual should be below initial tone",Math.sqrt(after/an)<Math.sqrt(before/bn)*0.90);
    }

    @Test public void slightSourceFrequencyErrorIsTracked(){
        FixedToneCancellationLab lab=new FixedToneCancellationLab();lab.setUserOutputScale(1f);
        final int sr=48000,delay=23,total=sr*38;float[] ring=new float[512];int p=0;
        for(int n=0;n<total;n++){
            double d=0.0015*Math.cos(2*Math.PI*120.07*n/sr-0.4);
            float mic=(float)(d+0.20*ring[(p-delay+ring.length)%ring.length]);
            float out=lab.process(mic);ring[p]=out;if(++p==ring.length)p=0;
        }
        assertEquals(120.07,lab.trackedFrequencyHz(),0.12);
        assertTrue(lab.bestImprovementDb()>0.5);
    }
}
