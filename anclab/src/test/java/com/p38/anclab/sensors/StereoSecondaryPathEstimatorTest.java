package com.p38.anclab.sensors;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class StereoSecondaryPathEstimatorTest {
    @Test public void fitsKnownDelayedPath(){
        int n=6000,lag=91;float[] probe=new float[n],response=new float[n];int state=0x12345;
        for(int i=0;i<n;i++){state=state*1103515245+12345;probe[i]=((state>>>16)&1)==0?-0.1f:0.1f;}
        for(int i=0;i<n-lag-2;i++)response[i+lag]=0.7f*probe[i]+0.15f*(i>0?probe[i-1]:0);
        StereoSecondaryPathEstimator.Path p=StereoSecondaryPathEstimator.fit(probe,response,48000,300,64);
        assertEquals(lag,p.bulkDelaySamples(),1);assertTrue(p.quality()>0.5);assertTrue(p.fir().length==64);
    }
}
