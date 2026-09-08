package com.p38.anclab.sensors;

/** Fits each stereo output->error-microphone path from isolated/decorrelated calibration probes. */
public final class StereoSecondaryPathEstimator {
    private StereoSecondaryPathEstimator(){}
    public record Path(float[] fir,int bulkDelaySamples,double quality){}

    public static StereoSecondaryPathCalibration estimate(float[] leftProbe,float[] rightProbe,float[] leftError,float[] rightError,int sampleRate,int maxDelaySamples,int taps){
        if(leftProbe==null||rightProbe==null||leftError==null||rightError==null)throw new IllegalArgumentException("all stereo calibration traces are required");
        Path ll=fit(leftProbe,leftError,sampleRate,maxDelaySamples,taps),lr=fit(leftProbe,rightError,sampleRate,maxDelaySamples,taps);
        Path rl=fit(rightProbe,leftError,sampleRate,maxDelaySamples,taps),rr=fit(rightProbe,rightError,sampleRate,maxDelaySamples,taps);
        StereoSecondaryPathCalibration c=new StereoSecondaryPathCalibration();c.ll=ll.fir;c.lr=lr.fir;c.rl=rl.fir;c.rr=rr.fir;c.delayLl=ll.bulkDelaySamples;c.delayLr=lr.bulkDelaySamples;c.delayRl=rl.bulkDelaySamples;c.delayRr=rr.bulkDelaySamples;c.qualityLl=ll.quality;c.qualityLr=lr.quality;c.qualityRl=rl.quality;c.qualityRr=rr.quality;c.utcMs=System.currentTimeMillis();return c;
    }

    public static Path fit(float[] probe,float[] response,int sampleRate,int maxDelaySamples,int taps){
        if(probe.length<256||response.length<256||sampleRate<=0||taps<8)throw new IllegalArgumentException("invalid path calibration data");
        SensorCalibrationEngine.LatencyCalibration latency=SensorCalibrationEngine.estimateLatency(probe,response,sampleRate,maxDelaySamples);
        int lag=latency.lagSamples(),n=Math.min(probe.length,response.length-lag);if(n<=taps+32)throw new IllegalArgumentException("insufficient aligned path data");
        float[] h=new float[taps],hist=new float[taps];int hp=0;double targetEnergy=1e-12,errorEnergy=1e-12;float mu=.30f;
        for(int i=0;i<n;i++){
            float x=probe[i];hist[hp]=x;float y=0,norm=1e-7f;int p=hp;for(int k=0;k<taps;k++){float v=hist[p];y+=h[k]*v;norm+=v*v;if(--p<0)p=taps-1;}
            float target=response[i+lag],err=target-y,step=mu*err/norm;p=hp;for(int k=0;k<taps;k++){h[k]+=step*hist[p];if(--p<0)p=taps-1;}
            if(i>taps*2){targetEnergy+=target*(double)target;errorEnergy+=err*(double)err;}if(++hp==taps)hp=0;
        }
        double fit=1.0-errorEnergy/targetEnergy,quality=Math.max(0,Math.min(1,Math.min(Math.abs(latency.correlation()),fit)));
        return new Path(h,lag,quality);
    }
}
