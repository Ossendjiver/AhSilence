package com.p38.anclab.sensors;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.assertTrue;

public final class SensorCoherenceScorerTest {
    @Test public void coherentReferenceScoresAboveIndependentNoise(){
        int sr=2000,n=8192;float[] ref=new float[n],err=new float[n],noise=new float[n];Random r=new Random(7);
        for(int i=0;i<n;i++){ref[i]=(float)Math.sin(2*Math.PI*68*i/sr);err[i]=(float)(0.6*Math.sin(2*Math.PI*68*i/sr+0.4)+0.08*r.nextGaussian());noise[i]=(float)r.nextGaussian();}
        SensorCoherenceScorer.Result good=SensorCoherenceScorer.scoreAtFrequency(ref,err,sr,68,512,18,20000);
        SensorCoherenceScorer.Result bad=SensorCoherenceScorer.scoreAtFrequency(noise,err,sr,68,512,18,20000);
        assertTrue(good.coherence()>0.80);assertTrue(good.admissionScore()>bad.admissionScore()+0.3);
    }
}
