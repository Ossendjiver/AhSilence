package com.p38.anclab.dsp;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertTrue;

public final class FeedbackFxNlmsSafetyTest {
    @Test public void railGuardStillTripsAtTenPercentBroadbandCeiling() throws Exception {
        FeedbackFxNlms fx=new FeedbackFxNlms(new float[]{1f},2400,128,0.08f);
        fx.setUserOutputScale(0.10f); // 0.08 * 0.10 * 0.25 = exactly 0.002 model ceiling.
        fx.setAdaptationRate(0f);
        Field wf=FeedbackFxNlms.class.getDeclaredField("w");wf.setAccessible(true);
        float[] w=(float[])wf.get(fx);w[0]=32f;
        for(int i=0;i<48000&&!fx.isLatchedOff();i++){
            float sample=(float)(0.08*Math.sin(2.0*Math.PI*100.0*i/48000.0));
            fx.process(sample);
        }
        assertTrue("0.002 ceiling must no longer bypass rail monitoring",fx.isLatchedOff());
        assertTrue(fx.safetyTrips()>0);
    }
}
