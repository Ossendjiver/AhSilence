package com.p38.anclab.dsp;

import java.util.Arrays;
import java.util.List;

/**
 * Shared stable-line discovery for profiles that do not have telemetry. It deliberately does not
 * own an output controller: callers can log/learn the discovered lines or hand them to a separate
 * narrowband layer. This lets headphone prediction benefit from the vehicle detector without
 * changing the existing headphone controller's authority.
 */
public final class PredictableFrequencyDiscovery {
    private static final int INPUT_RATE=48000;
    private static final int DECIMATION=8;
    private static final int DECIMATED_RATE=INPUT_RATE/DECIMATION;
    private static final int WINDOW=16384;
    private static final int UPDATE_SAMPLES=DECIMATED_RATE/2;
    private final double minHz,maxHz;
    private final float[] ring=new float[WINDOW];
    private final BroadbandDetector detector=new BroadbandDetector();
    private int write=0,count=0,decimator=0,sinceUpdate=0;
    private long inputSamplesSeen=0;
    private volatile double[] latest=new double[0];

    public PredictableFrequencyDiscovery(double minHz,double maxHz){this.minHz=minHz;this.maxHz=maxHz;}

    /** Hot-loop form: detector age is derived from the audio sample clock, not a wall-clock syscall. */
    public void observe(float sample){
        inputSamplesSeen++;
        if(++decimator<DECIMATION)return;decimator=0;
        ring[write]=sample;if(++write==ring.length)write=0;if(count<ring.length)count++;
        if(++sinceUpdate<UPDATE_SAMPLES||count<ring.length)return;sinceUpdate=0;
        update(Math.round(inputSamplesSeen*1000.0/INPUT_RATE));
    }

    /** Deterministic test/replay hook. */
    void observeAt(float sample,long nowMs){
        inputSamplesSeen++;
        if(++decimator<DECIMATION)return;decimator=0;
        ring[write]=sample;if(++write==ring.length)write=0;if(count<ring.length)count++;
        if(++sinceUpdate<UPDATE_SAMPLES||count<ring.length)return;sinceUpdate=0;update(nowMs);
    }

    private void update(long nowMs){
        float[] ordered=new float[ring.length];for(int i=0;i<ring.length;i++)ordered[i]=ring[(write+i)%ring.length];
        List<SpectrumAnalyzer.DetectedTone> peaks=SpectrumAnalyzer.findPeaks(ordered,0,DECIMATED_RATE,minHz,maxHz,12,1.0);
        List<BroadbandDetector.Candidate> ready=detector.update(peaks,nowMs);
        double[] f=new double[ready.size()];for(int i=0;i<f.length;i++)f[i]=ready.get(i).frequencyHz();Arrays.sort(f);latest=f;
    }

    public double[] frequenciesHz(){return Arrays.copyOf(latest,latest.length);}
    public void reset(){Arrays.fill(ring,0f);write=count=decimator=sinceUpdate=0;inputSamplesSeen=0;latest=new double[0];detector.reset();}
}
