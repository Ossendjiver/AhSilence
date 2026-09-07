package com.p38.anclab.dsp;

import com.p38.anclab.profile.MechanicalFrequency;
import com.p38.anclab.telemetry.VehicleTelemetryRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coherent narrowband vehicle cancellation bank with live GPS/OBD order tracking.
 *
 * Each lane keeps its controller and oscillator while its telemetry-predicted frequency moves.
 * Around every prediction the selected cabin microphone is searched within +/-1.2 Hz. A quiet/agile
 * Kalman bank suppresses GPS/OBD jitter and follows a repeatable moving acoustic peak. Valid peaks
 * are also fed back to VehicleTelemetryRuntime, which persists a small correction to the physical
 * speed/RPM model for subsequent runs.
 *
 * Speculative broadband remains outside this class and receives notched microphone audio, so it
 * cannot compete with these predictable lanes.
 */
public final class VehicleNarrowbandBank {
    private static final int SAMPLE_RATE=48000;
    private static final int DECIMATION=96;
    private static final double ANALYSIS_RATE=SAMPLE_RATE/(double)DECIMATION;
    private static final int RING_SAMPLES=1100;
    private static final int ANALYSIS_INTERVAL_SAMPLES=50;
    private static final double TWO_PI=Math.PI*2.0;
    private static final double SEARCH_HALF_WIDTH_HZ=1.20;

    private final ButterworthLowPass analysisLowPass=new ButterworthLowPass(SAMPLE_RATE,210.0);
    private final DcBlocker analysisDc=new DcBlocker(Math.exp(-2.0*Math.PI*3.0/SAMPLE_RATE));
    private final float[] ring=new float[RING_SAMPLES];
    private final VehicleTelemetryRuntime telemetry;
    private int ringPos=0,ringCount=0,decimator=0,sinceAnalysis=0;
    private long totalAnalysisSamples=0;
    private Lane[] lanes=new Lane[0];
    private float totalCeiling=0.08f;
    private float userScale=0.50f;
    private float outRms=0f;
    private volatile String status="Predictable lanes ready";
    private volatile long frequencyRevision=0L;

    public VehicleNarrowbandBank(List<MechanicalFrequency> models,VehicleTelemetryRuntime telemetry,
                                 float safeOutputCeiling,float userScale){
        this.telemetry=telemetry;
        totalCeiling=clamp(Math.abs(safeOutputCeiling),0.005f,0.15f);
        this.userScale=clamp(userScale,0f,1f);
        rebuild(models,System.currentTimeMillis());
    }

    public synchronized double[] frequenciesHz(){
        List<Double> out=new ArrayList<>();
        for(Lane l:lanes)if(l.available&&Double.isFinite(l.currentFrequencyHz)&&l.currentFrequencyHz>=8&&l.currentFrequencyHz<=200)out.add(l.currentFrequencyHz);
        double[] f=new double[out.size()];for(int i=0;i<f.length;i++)f[i]=out.get(i);return f;
    }
    public long frequencyRevision(){return frequencyRevision;}
    public synchronized void setUserOutputScale(float scale){userScale=clamp(scale,0f,1f);redistributeLimits();}
    public float outputRms(){return(float)Math.sqrt(Math.max(0f,outRms));}
    public String status(){return status;}
    public int activeLaneCount(){int n=0;for(Lane l:lanes)if(l.available&&l.controller.output().gain()>1e-5)n++;return n;}

    private void rebuild(List<MechanicalFrequency> requested,long now){
        if(requested==null)requested=List.of();
        List<Lane> clean=new ArrayList<>();
        for(MechanicalFrequency m:requested){
            if(m==null||!m.enabled())continue;
            double initial=telemetry==null?m.frequencyHz():telemetry.predictedHz(m);
            if(!Double.isFinite(initial))initial=m.frequencyHz();
            if(initial<8.0||initial>200.0)continue;
            boolean duplicate=false;for(Lane existing:clean)if(Math.abs(existing.currentFrequencyHz-initial)<0.35){duplicate=true;break;}
            if(duplicate)continue;
            clean.add(new Lane(m,initial));
        }
        lanes=clean.toArray(new Lane[0]);redistributeLimits();
        for(Lane l:lanes){l.referenceEpoch=totalAnalysisSamples;l.referencePhase=l.oscillator.phase;l.tracker.reset(l.currentFrequencyHz,now);l.controller.startTracking(now,perLaneLimit(),l.currentFrequencyHz,l.label,true);}
        frequencyRevision++;
        status=lanes.length==0?"No configured predictable lanes":"Predictable narrowband · "+lanes.length+" telemetry lane"+(lanes.length==1?"":"s");
    }

    private void redistributeLimits(){double each=perLaneLimit();for(Lane l:lanes)l.controller.setMaximumGain(each);}
    private double perLaneLimit(){return Math.max(0.0001,totalCeiling*Math.max(0.02f,userScale)/Math.max(1,lanes.length));}

    /** Returns model-domain narrowband anti-noise for one 48 kHz microphone sample. */
    public float process(float microphone){
        if(telemetry!=null)telemetry.touch();
        double filtered=analysisLowPass.process(analysisDc.process(microphone));
        if(++decimator>=DECIMATION){decimator=0;ring[ringPos]=(float)filtered;if(++ringPos==ring.length)ringPos=0;if(ringCount<ring.length)ringCount++;totalAnalysisSamples++;if(++sinceAnalysis>=ANALYSIS_INTERVAL_SAMPLES){sinceAnalysis=0;analyze(System.currentTimeMillis());}}

        double value=0.0;
        double coefficientSmoothing=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.030));
        double frequencySmoothing=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.025));
        for(Lane l:lanes){
            AutoController.Output o=l.controller.output();
            double targetGain=l.available?o.gain():0.0;
            l.oscillator.targetFrequencyHz=l.available?o.frequencyHz():l.oscillator.frequencyHz;
            l.oscillator.targetReal=targetGain*Math.cos(o.phaseRadians());
            l.oscillator.targetImag=targetGain*Math.sin(o.phaseRadians());
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
        int available=0,cancelling=0,learning=0;
        boolean moved=false;
        for(Lane l:lanes){
            double predicted=telemetry==null?l.model.frequencyHz():telemetry.predictedHz(l.model);
            boolean valid=Double.isFinite(predicted)&&predicted>=8.0&&predicted<=200.0;
            if(!valid){
                if(l.available){l.available=false;l.controller.stop();moved=true;}
                continue;
            }
            available++;
            if(!l.available){
                l.available=true;l.currentFrequencyHz=predicted;l.tracker.reset(predicted,now);
                l.referenceEpoch=totalAnalysisSamples;l.referencePhase=l.oscillator.phase;
                l.controller.startTracking(now,perLaneLimit(),predicted,l.label,true);moved=true;
            }

            double lo=Math.max(8.0,predicted-SEARCH_HALF_WIDTH_HZ),hi=Math.min(200.0,predicted+SEARCH_HALF_WIDTH_HZ);
            SpectrumSnapshot search=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,lo,hi,predicted,l.referenceEpoch,l.referencePhase);
            boolean acousticLock=search.peakDbFs()>-78.0&&search.contrastDb()>2.2&&Math.abs(search.peakFrequencyHz()-predicted)<=SEARCH_HALF_WIDTH_HZ;
            double refined=predicted;
            if(acousticLock){
                refined=l.tracker.update(search.peakFrequencyHz(),now);
                if(telemetry!=null){telemetry.observe(l.model,search.peakFrequencyHz(),search.contrastDb(),search.peakDbFs());learning++;}
            }else{
                double estimate=l.tracker.estimateHz();
                // Return gradually toward the physical prediction if microphone evidence disappears.
                if(Double.isFinite(estimate)&&Math.abs(estimate-predicted)<SEARCH_HALF_WIDTH_HZ)refined=0.85*estimate+0.15*predicted;
            }
            refined=Math.max(8.0,Math.min(200.0,refined));
            if(Math.abs(refined-l.currentFrequencyHz)>0.025){l.currentFrequencyHz=refined;moved=true;}
            l.controller.followFrequency(refined,now);

            SpectrumSnapshot exact=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,
                    Math.max(8.0,refined-0.15),Math.min(200.0,refined+0.15),refined,l.referenceEpoch,l.referencePhase);
            l.controller.update(exact,now);
            if(l.controller.output().gain()>1e-5)cancelling++;
        }
        if(moved)frequencyRevision++;
        String telem=telemetry==null?"":telemetry.status();
        status=String.format(Locale.US,"Predictable narrowband · %d/%d telemetry · %d cancelling · %d learning%s",
                available,lanes.length,cancelling,learning,telem.isEmpty()?"":"\n"+telem);
    }

    private float[] copyRing(){float[] out=new float[ringCount];int start=ringPos-ringCount;if(start<0)start+=ring.length;for(int i=0;i<ringCount;i++)out[i]=ring[(start+i)%ring.length];return out;}

    private static final class Lane {
        final MechanicalFrequency model;final String label;final AutoController controller=new AutoController();final AdaptiveFrequencyTracker tracker=new AdaptiveFrequencyTracker();final Oscillator oscillator=new Oscillator();long referenceEpoch;double referencePhase;double currentFrequencyHz;boolean available=true;
        Lane(MechanicalFrequency model,double f){this.model=model;label=model.name();currentFrequencyHz=f;oscillator.frequencyHz=f;oscillator.targetFrequencyHz=f;}
    }
    private static final class Oscillator {double phase=0,frequencyHz=0,targetFrequencyHz=0,real=0,imag=0,targetReal=0,targetImag=0;}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
