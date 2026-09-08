package com.p38.anclab.dsp;

import java.util.Arrays;

/** Reports persistent broadband energy even when it is not coherent enough to cancel. */
public final class StationaryNoiseProfiler {
    public record Snapshot(double rawRmsDbFs,double ancBandRmsDbFs,double variabilityDb,boolean stationary,String status){}
    private static final int SAMPLE_RATE=48000;
    private static final int WINDOW=SAMPLE_RATE;
    private final HeadphoneBandLimiter ancBand=new HeadphoneBandLimiter(SAMPLE_RATE,true);
    private final double[] history=new double[6];
    private int historyCount=0,historyPos=0,samples=0;
    private double rawEnergy=0,bandEnergy=0;
    private volatile Snapshot latest=new Snapshot(-120,-120,Double.NaN,false,"profiling…");

    public void observe(float raw){
        float band=ancBand.process(raw);rawEnergy+=raw*(double)raw;bandEnergy+=band*(double)band;
        if(++samples<WINDOW)return;
        double rawDb=db(Math.sqrt(rawEnergy/samples)),bandDb=db(Math.sqrt(bandEnergy/samples));
        history[historyPos]=bandDb;if(++historyPos==history.length)historyPos=0;if(historyCount<history.length)historyCount++;
        double mean=0;for(int i=0;i<historyCount;i++)mean+=history[i];mean/=Math.max(1,historyCount);
        double var=0;for(int i=0;i<historyCount;i++){double d=history[i]-mean;var+=d*d;}double sd=Math.sqrt(var/Math.max(1,historyCount));
        boolean stable=historyCount>=4&&sd<=0.75;
        String status=String.format(java.util.Locale.US,"raw %.1f dBFS · ANC band %.1f dBFS · %s (σ %.2f dB)",rawDb,bandDb,stable?"stationary":"varying",sd);
        latest=new Snapshot(rawDb,bandDb,sd,stable,status);samples=0;rawEnergy=bandEnergy=0;
    }
    public Snapshot snapshot(){return latest;}
    public void reset(){ancBand.reset();Arrays.fill(history,0);historyCount=historyPos=samples=0;rawEnergy=bandEnergy=0;latest=new Snapshot(-120,-120,Double.NaN,false,"profiling…");}
    private static double db(double v){return 20.0*Math.log10(Math.max(1.0e-12,v));}
}
