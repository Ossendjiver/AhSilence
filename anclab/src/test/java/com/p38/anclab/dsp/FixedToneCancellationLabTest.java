package com.p38.anclab.dsp;

import org.junit.Test;
import static org.junit.Assert.*;

public class FixedToneCancellationLabTest {
    private static double run(double hz,double sourceHz,int seconds){
        FixedToneCancellationLab lab=new FixedToneCancellationLab(hz);lab.setUserOutputScale(1f);
        final int sr=48000,delay=37,total=sr*seconds;float[] ring=new float[1024];int p=0;double before=0,after=0;int bn=0,an=0;
        for(int n=0;n<total;n++){double d=0.0015*Math.cos(2*Math.PI*sourceHz*n/sr+0.7);double secondary=0.22*ring[(p-delay+ring.length)%ring.length];float mic=(float)(d+secondary);float out=lab.process(mic);ring[p]=out;if(++p==ring.length)p=0;if(n<sr*2){before+=mic*mic;bn++;}if(n>total-sr*4){after+=mic*mic;an++;}}
        assertEquals(hz,lab.trackedFrequencyHz(),1e-9);
        assertTrue("physical search should find benefit at "+hz+" Hz",lab.bestImprovementDb()>0.5);
        return 20*Math.log10(Math.sqrt(before/bn)/Math.sqrt(after/an));
    }
    @Test public void exact120HzDoesNotDriftAndCancels(){assertTrue(run(120,120,45)>0.5);}
    @Test public void requestedSuiteFrequenciesAllRemainExact(){for(double f:FixedToneCancellationLab.TEST_FREQUENCIES_HZ){FixedToneCancellationLab lab=new FixedToneCancellationLab(f);assertEquals(f,lab.trackedFrequencyHz(),0.0);}}
    @Test public void fortyFiveAndFiveHundredCanSearchPhysicalPath(){assertTrue(run(45,45,55)>0.5);assertTrue(run(500,500,40)>0.5);}
}
