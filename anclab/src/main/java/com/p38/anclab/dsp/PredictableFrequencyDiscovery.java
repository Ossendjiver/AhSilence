package com.p38.anclab.dsp;

import java.util.Arrays;
import java.util.List;

/** Shared persistent-line discovery for profiles without telemetry. */
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
    private volatile List<BroadbandDetector.Candidate> latestCandidates=List.of();

    public PredictableFrequencyDiscovery(double minHz,double maxHz){this.minHz=minHz;this.maxHz=maxHz;}

    public void observe(float sample){
        inputSamplesSeen++;
        if(++decimator<DECIMATION)return;decimator=0;
        ring[write]=sample;if(++write==ring.length)write=0;if(count<ring.length)count++;
        if(++sinceUpdate<UPDATE_SAMPLES||count<ring.length)return;sinceUpdate=0;
        update(Math.round(inputSamplesSeen*1000.0/INPUT_RATE));
    }

    public void observe(float sample,long ignoredNowMs){observe(sample);}

    void observeAt(float sample,long nowMs){
        inputSamplesSeen++;
        if(++decimator<DECIMATION)return;decimator=0;
        ring[write]=sample;if(++write==ring.length)write=0;if(count<ring.length)count++;
        if(++sinceUpdate<UPDATE_SAMPLES||count<ring.length)return;sinceUpdate=0;update(nowMs);
    }

    private void update(long nowMs){
        float[] ordered=new float[ring.length];for(int i=0;i<ring.length;i++)ordered[i]=ring[(write+i)%ring.length];
        List<SpectrumAnalyzer.DetectedTone> peaks=SpectrumAnalyzer.findPeaks(ordered,0,DECIMATED_RATE,minHz,maxHz,18,1.0);
        List<BroadbandDetector.Candidate> ready=detector.update(peaks,nowMs);
        latestCandidates=List.copyOf(ready);
        double[] f=new double[ready.size()];for(int i=0;i<f.length;i++)f[i]=ready.get(i).frequencyHz();Arrays.sort(f);latest=f;
    }

    public double[] frequenciesHz(){return Arrays.copyOf(latest,latest.length);}
    public List<BroadbandDetector.Candidate> candidates(){return latestCandidates;}
    public String summary(){
        if(latestCandidates.isEmpty())return"no mature predictable lines";
        StringBuilder b=new StringBuilder();int n=Math.min(6,latestCandidates.size());
        for(int i=0;i<n;i++){
            BroadbandDetector.Candidate c=latestCandidates.get(i);if(i>0)b.append(" | ");
            b.append(String.format(java.util.Locale.US,"%.1fHz %.0fdBFS +%.1fdB",c.frequencyHz(),c.dbFs(),c.prominenceDb()));
        }
        return b.toString();
    }
    public void reset(){Arrays.fill(ring,0f);write=count=decimator=sinceUpdate=0;inputSamplesSeen=0;latest=new double[0];latestCandidates=List.of();detector.reset();}
}
