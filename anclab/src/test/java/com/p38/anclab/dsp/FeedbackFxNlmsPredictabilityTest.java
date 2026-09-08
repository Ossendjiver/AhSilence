package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FeedbackFxNlmsPredictabilityTest {
    @Test public void incoherentResidualDoesNotAcquireBroadbandAuthority(){
        FeedbackFxNlms fx=new FeedbackFxNlms(new float[]{1f},2400,128,0.08f);fx.setUserOutputScale(0.5f);
        int state=0x13579bdf;double outEnergy=0;
        for(int i=0;i<48000*3;i++){state=state*1664525+1013904223;float x=(((state>>>8)&0xffff)/32768f-1f)*0.03f;float y=fx.process(x);outEnergy+=y*(double)y;}
        assertTrue("random residual should remain below predictability gate",fx.predictability()<0.55f);
        assertTrue("random residual should not develop meaningful output",Math.sqrt(outEnergy/(48000.0*3))<0.0005);
        assertFalse("gated random residual should not need a safety latch",fx.isLatchedOff());
    }
}
