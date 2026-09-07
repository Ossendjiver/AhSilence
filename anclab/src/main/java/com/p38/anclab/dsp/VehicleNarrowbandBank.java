package com.p38.anclab.dsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Separate coherent narrowband vehicle cancellation bank.
 *
 * Each configured predictable frequency owns an AutoController, which measures the selected
 * cabin microphone at the exact target, performs balanced +/- acoustic-path probes and drives a
 * phase-continuous oscillator. The speculative broadband FxNLMS is intentionally outside this
 * class and receives notched microphone audio so it cannot compete with these lanes.
 *
 * This first restored runtime consumes the currently supplied frequency targets. Call
 * setFrequencies() when GPS/OBD prediction moves those targets; the bank will rebuild safely.
 */
public final class VehicleNarrowbandBank {
    private static final int SAMPLE_RATE=48000;
    private static final int DECIMATION=96;              // 500 Hz analysis stream
    private static final double ANALYSIS_RATE=SAMPLE_RATE/(double)DECIMATION;
    private static final int RING_SAMPLES=1100;           // 2.2 seconds
    private static final int ANALYSIS_INTERVAL_SAMPLES=50; // 100 ms at 500 Hz
    private static final double TWO_PI=Math.PI*2.0;

    private final ButterworthLowPass analysisLowPass=new ButterworthLowPass(SAMPLE_RATE,210.0);
    private final DcBlocker analysisDc=new DcBlocker(Math.exp(-2.0*Math.PI*3.0/SAMPLE_RATE));
    private final float[] ring=new float[RING_SAMPLES];
    private int ringPos=0,ringCount=0,decimator=0,sinceAnalysis=0;
    private long totalAnalysisSamples=0;
    private Lane[] lanes=new Lane[0];
    private float totalCeiling=0.08f;
    private float userScale=0.50f;
    private float outRms=0f;
    private volatile String status="Predictable lanes ready";

    public VehicleNarrowbandBank(double[] frequenciesHz,float safeOutputCeiling,float userScale){
        totalCeiling=clamp(Math.abs(safeOutputCeiling),0.005f,0.15f);
        this.userScale=clamp(userScale,0f,1f);
        rebuild(frequenciesHz,System.currentTimeMillis());
    }

    public synchronized void setFrequencies(double[] frequenciesHz){rebuild(frequenciesHz,System.currentTimeMillis());}
    public synchronized double[] frequenciesHz(){double[] f=new double[lanes.length];for(int i=0;i<lanes.length;i++)f[i]=lanes[i].frequencyHz;return f;}
    public synchronized void setUserOutputScale(float scale){userScale=clamp(scale,0f,1f);redistributeLimits();}
    public float outputRms(){return(float)Math.sqrt(Math.max(0f,outRms));}
    public String status(){return status;}
    public int activeLaneCount(){int n=0;for(Lane l:lanes)if(l.controller.output().gain()>1e-5)n++;return n;}

    private void rebuild(double[] requested,long now){
        if(requested==null)requested=new double[0];
        List<Double> clean=new ArrayList<>();double[] sorted=Arrays.copyOf(requested,requested.length);Arrays.sort(sorted);
        for(double f:sorted){if(!Double.isFinite(f)||f<8.0||f>200.0)continue;if(!clean.isEmpty()&&Math.abs(clean.get(clean.size()-1)-f)<0.35)continue;clean.add(f);}
        Lane[] next=new Lane[clean.size()];
        for(int i=0;i<next.length;i++){double f=clean.get(i);next[i]=new Lane(f,"Predictable "+String.format(java.util.Locale.US,"%.1f Hz",f));next[i].referenceEpoch=totalAnalysisSamples;next[i].referencePhase=next[i].oscillator.phase;}
        lanes=next;redistributeLimits();
        for(Lane l:lanes)l.controller.startTracking(now,perLaneLimit(),l.frequencyHz,l.label,true);
        status=lanes.length==0?"No configured predictable lanes":"Predictable narrowband · "+lanes.length+" lane"+(lanes.length==1?"":"s");
    }

    private void redistributeLimits(){double each=perLaneLimit();for(Lane l:lanes)l.controller.setMaximumGain(each);}
    private double perLaneLimit(){return Math.max(0.0001,totalCeiling*Math.max(0.02f,userScale)/Math.max(1,lanes.length));}

    /** Returns model-domain narrowband anti-noise for one 48 kHz microphone sample. */
    public float process(float microphone){
        double filtered=analysisLowPass.process(analysisDc.process(microphone));
        if(++decimator>=DECIMATION){decimator=0;ring[ringPos]=(float)filtered;if(++ringPos==ring.length)ringPos=0;if(ringCount<ring.length)ringCount++;totalAnalysisSamples++;if(++sinceAnalysis>=ANALYSIS_INTERVAL_SAMPLES){sinceAnalysis=0;analyze(System.currentTimeMillis());}}

        double value=0.0;
        double coefficientSmoothing=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.030));
        double frequencySmoothing=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.025));
        for(Lane l:lanes){
            AutoController.Output o=l.controller.output();
            l.oscillator.targetFrequencyHz=o.frequencyHz();
            l.oscillator.targetReal=o.gain()*Math.cos(o.phaseRadians());
            l.oscillator.targetImag=o.gain()*Math.sin(o.phaseRadians());
            l.oscillator.frequencyHz+=frequencySmoothing*(l.oscillator.targetFrequencyHz-l.oscillator.frequencyHz);
            l.oscillator.real+=coefficientSmoothing*(l.oscillator.targetReal-l.oscillator.real);
            l.oscillator.imag+=coefficientSmoothing*(l.oscillator.targetImag-l.oscillator.imag);
            value+=l.oscillator.real*Math.cos(l.oscillator.phase)-l.oscillator.imag*Math.sin(l.oscillator.phase);
            l.oscillator.phase+=TWO_PI*l.oscillator.frequencyHz/SAMPLE_RATE;if(l.oscillator.phase>=TWO_PI)l.oscillator.phase-=TWO_PI;
        }
        float ceiling=totalCeiling*userScale;
        float out=clamp((float)value,-ceiling,ceiling);
        outRms=0.995f*outRms+0.005f*out*out;
        return out;
    }

    private void analyze(long now){
        if(ringCount<Math.min(300,RING_SAMPLES))return;
        float[] window=copyRing();long first=totalAnalysisSamples-window.length;
        int active=0;
        for(Lane l:lanes){
            double target=l.controller.output().frequencyHz();
            double min=Math.max(8.0,target-0.15),max=Math.min(200.0,target+0.15);
            SpectrumSnapshot snapshot=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,min,max,target,l.referenceEpoch,l.referencePhase);
            l.controller.update(snapshot,now);
            if(l.controller.output().gain()>1e-5)active++;
        }
        status="Predictable narrowband · "+lanes.length+" monitored · "+active+" cancelling";
    }

    private float[] copyRing(){float[] out=new float[ringCount];int start=ringPos-ringCount;if(start<0)start+=ring.length;for(int i=0;i<ringCount;i++)out[i]=ring[(start+i)%ring.length];return out;}

    private static final class Lane {
        final double frequencyHz;final String label;final AutoController controller=new AutoController();final Oscillator oscillator=new Oscillator();long referenceEpoch;double referencePhase;
        Lane(double f,String label){frequencyHz=f;this.label=label;oscillator.frequencyHz=f;oscillator.targetFrequencyHz=f;}
    }
    private static final class Oscillator {double phase=0,frequencyHz=0,targetFrequencyHz=0,real=0,imag=0,targetReal=0,targetImag=0;}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
