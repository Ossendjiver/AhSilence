package com.p38.anclab.dsp;

import java.util.Arrays;

/**
 * Feedback filtered-x normalized LMS controller.
 *
 * Error microphone model: e[n] = d[n] + S(z)y[n].
 * The disturbance reference is estimated as d_hat[n] = e[n] - S_hat(z)y[n].
 * The controller output y[n] = W(z)d_hat[n]. W is adapted using the reference
 * filtered by the measured secondary path S_hat.
 */
public final class FeedbackFxNlms {
    private final int taps;
    private final float[] w;
    private final float[] xHist;
    private final float[] xfHist;
    private final float[] s;
    private final float[] yDelay;
    private final float[] refFirHistory = new float[256];
    private int xPos = 0, xfPos = 0, yPos = 0, refPos = 0;
    private final int delaySamples;
    private float mu = 0.06f;
    private float leakage = 0.00002f;
    private float outputCeiling = 0.22f;
    private float dcX1 = 0f, dcY1 = 0f;
    private float inRms = 0f, outRms = 0f;

    public FeedbackFxNlms(float[] secondaryPath, int delaySamples, int controllerTaps, float ceiling) {
        taps = Math.max(16, controllerTaps);
        w = new float[taps];
        xHist = new float[taps];
        xfHist = new float[taps];
        s = secondaryPath == null || secondaryPath.length == 0 ? new float[]{1f} : Arrays.copyOf(secondaryPath, secondaryPath.length);
        this.delaySamples = Math.max(0, delaySamples);
        yDelay = new float[Math.max(512, this.delaySamples + s.length + 8)];
        outputCeiling = Math.max(0.02f, Math.min(0.5f, ceiling));
    }

    public void setAdaptationRate(float v) { mu = Math.max(0f, Math.min(0.25f, v)); }
    public void reset() {
        Arrays.fill(w,0f); Arrays.fill(xHist,0f); Arrays.fill(xfHist,0f); Arrays.fill(yDelay,0f); Arrays.fill(refFirHistory,0f);
        xPos=xfPos=yPos=refPos=0; dcX1=dcY1=inRms=outRms=0f;
    }

    public float process(float error) {
        float hp = error - dcX1 + 0.995f * dcY1;
        dcX1 = error; dcY1 = hp;

        float reference = hp - convolveDelayedOutput();
        xHist[xPos] = reference;
        float y = softLimit(dotCircular(w,xHist,xPos), outputCeiling);
        yDelay[yPos] = y;

        float xf = convolveReference(reference);
        xfHist[xfPos] = xf;
        float norm = 1e-5f;
        for (float v : xfHist) norm += v*v;
        float step = mu * hp / norm;
        for (int k=0;k<taps;k++) {
            int idx=xfPos-k; if(idx<0) idx+=taps;
            w[k]=(1f-leakage)*w[k]-step*xfHist[idx];
        }

        if(++xPos==taps)xPos=0; if(++xfPos==taps)xfPos=0; if(++yPos==yDelay.length)yPos=0;
        inRms=0.995f*inRms+0.005f*hp*hp; outRms=0.995f*outRms+0.005f*y*y;
        return y;
    }

    public float inputRms(){return (float)Math.sqrt(Math.max(0f,inRms));}
    public float outputRms(){return (float)Math.sqrt(Math.max(0f,outRms));}

    private float dotCircular(float[] c,float[] h,int newest){float sum=0;int p=newest;for(float v:c){sum+=v*h[p];if(--p<0)p=h.length-1;}return sum;}
    private float convolveDelayedOutput(){float sum=0;int p=yPos-delaySamples;while(p<0)p+=yDelay.length;for(float v:s){sum+=v*yDelay[p];if(--p<0)p=yDelay.length-1;}return sum;}
    private float convolveReference(float x){refFirHistory[refPos]=x;float sum=0;int p=refPos;int n=Math.min(s.length,refFirHistory.length);for(int k=0;k<n;k++){sum+=s[k]*refFirHistory[p];if(--p<0)p=refFirHistory.length-1;}if(++refPos==refFirHistory.length)refPos=0;return sum;}
    private float softLimit(float v,float ceiling){return Math.max(-ceiling,Math.min(ceiling,v));}
}
