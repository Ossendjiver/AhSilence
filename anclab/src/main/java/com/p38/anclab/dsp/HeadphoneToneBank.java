package com.p38.anclab.dsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Dedicated low-frequency tone layer for headphones. Persistent microphone-discovered tones are
 * given bounded sinusoidal controllers while the predictive FxNLMS handles the remaining residual.
 * This mirrors the successful vehicle narrowband architecture without requiring telemetry.
 */
public final class HeadphoneToneBank {
    private static final int SAMPLE_RATE=48000;
    private static final int DECIMATION=96;
    private static final double ANALYSIS_RATE=SAMPLE_RATE/(double)DECIMATION;
    private static final int RING_SAMPLES=1400;
    private static final int ANALYSIS_INTERVAL=50; // 100 ms at 500 Hz
    private static final int MAX_LANES=4;
    private static final double MIN_HZ=15.0,MAX_HZ=200.0;
    private static final double MIN_PROMINENCE_DB=10.0;
    private static final double ABSOLUTE_SANITY_FLOOR_DBFS=-110.0;
    private static final long STALE_MS=6000;
    private static final double MATCH_HZ=1.1;
    private static final double TWO_PI=Math.PI*2.0;

    private final ButterworthLowPass analysisLowPass=new ButterworthLowPass(SAMPLE_RATE,220.0);
    private final DcBlocker analysisDc=new DcBlocker(Math.exp(-2.0*Math.PI*5.0/SAMPLE_RATE));
    private final PredictableFrequencyDiscovery discovery=new PredictableFrequencyDiscovery(MIN_HZ,MAX_HZ);
    private final float[] ring=new float[RING_SAMPLES];
    private final List<Lane> lanes=new ArrayList<>();
    private int ringPos=0,ringCount=0,decimator=0,sinceAnalysis=0;
    private long analysisSamples=0;
    private float ceiling,userScale=0.5f,outRms=0f;
    private volatile String status="headphone tone discovery warming up";
    private volatile long revision=0;

    public HeadphoneToneBank(float safeOutputCeiling,float userScale){
        ceiling=clamp(Math.abs(safeOutputCeiling),0.005f,0.25f);this.userScale=clamp(userScale,0f,1f);
    }
    public synchronized void setUserOutputScale(float v){userScale=clamp(v,0f,1f);redistribute();}
    public float outputRms(){return(float)Math.sqrt(Math.max(0,outRms));}
    public String status(){return status;}
    public long frequencyRevision(){return revision;}
    public synchronized double[] frequenciesHz(){double[] f=new double[lanes.size()];for(int i=0;i<f.length;i++)f[i]=lanes.get(i).controller.output().frequencyHz();return f;}
    public List<BroadbandDetector.Candidate> candidates(){return discovery.candidates();}

    /** Model-domain anti-noise for one microphone sample. */
    public float process(float microphone){
        double filtered=analysisLowPass.process(analysisDc.process(microphone));
        discovery.observe((float)filtered);
        if(++decimator>=DECIMATION){decimator=0;ring[ringPos]=(float)filtered;if(++ringPos==ring.length)ringPos=0;if(ringCount<ring.length)ringCount++;analysisSamples++;if(++sinceAnalysis>=ANALYSIS_INTERVAL){sinceAnalysis=0;analyze(Math.round(analysisSamples*1000.0/ANALYSIS_RATE));}}
        double value=0;double coeffSmooth=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.030)),freqSmooth=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.025));
        synchronized(this){for(Lane l:lanes){AutoController.Output o=l.controller.output();l.osc.targetFrequency=o.frequencyHz();l.osc.frequency+=freqSmooth*(l.osc.targetFrequency-l.osc.frequency);double tr=o.gain()*Math.cos(o.phaseRadians()),ti=o.gain()*Math.sin(o.phaseRadians());l.osc.real+=coeffSmooth*(tr-l.osc.real);l.osc.imag+=coeffSmooth*(ti-l.osc.imag);value+=l.osc.real*Math.cos(l.osc.phase)-l.osc.imag*Math.sin(l.osc.phase);l.osc.phase+=TWO_PI*l.osc.frequency/SAMPLE_RATE;if(l.osc.phase>=TWO_PI)l.osc.phase-=TWO_PI;}}
        float out=clamp((float)value,-ceiling*userScale,ceiling*userScale);outRms=0.995f*outRms+0.005f*out*out;return out;
    }

    private synchronized void analyze(long now){
        if(ringCount<500)return;
        List<BroadbandDetector.Candidate> candidates=new ArrayList<>(discovery.candidates());
        candidates.removeIf(c->c.dbFs()<ABSOLUTE_SANITY_FLOOR_DBFS||c.prominenceDb()<MIN_PROMINENCE_DB);
        candidates.sort(Comparator.comparingDouble(BroadbandDetector.Candidate::score).reversed());
        for(Lane l:lanes)l.seen=false;
        for(BroadbandDetector.Candidate c:candidates){
            Lane best=null;double dBest=Double.POSITIVE_INFINITY;
            for(Lane l:lanes){double d=Math.abs(l.anchorHz-c.frequencyHz());if(d<MATCH_HZ&&d<dBest){best=l;dBest=d;}}
            if(best!=null){best.seen=true;best.lastStrongMs=now;best.lastDbFs=c.dbFs();best.lastProminenceDb=c.prominenceDb();best.targetHz=c.frequencyHz();continue;}
            if(lanes.size()>=MAX_LANES)continue;
            Lane l=new Lane(c.frequencyHz(),now);l.lastDbFs=c.dbFs();l.lastProminenceDb=c.prominenceDb();l.seen=true;lanes.add(l);redistribute();l.controller.startTracking(now,perLaneLimit(),l.targetHz,l.label,false);revision++;
        }
        float[] window=copyRing();long first=analysisSamples-window.length;int running=0;
        Iterator<Lane> it=lanes.iterator();
        while(it.hasNext()){
            Lane l=it.next();
            if(l.seen){double bounded=Math.max(l.anchorHz-1.5,Math.min(l.anchorHz+1.5,l.targetHz));l.controller.followFrequency(bounded,now);}
            double controlled=l.controller.output().frequencyHz();
            SpectrumSnapshot exact=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,Math.max(MIN_HZ,controlled-0.20),Math.min(MAX_HZ,controlled+0.20),controlled,l.referenceEpoch,l.osc.phase);
            l.controller.update(exact,now);
            if(l.controller.output().gain()>1e-5)running++;
            if(!l.seen&&l.controller.output().gain()<=1e-5&&now-l.lastStrongMs>STALE_MS){l.controller.stop();it.remove();revision++;}
        }
        if(lanes.isEmpty())status="no mature low-frequency tones · predictive residual only";
        else status=String.format(Locale.US,"%d tone lanes · %d active · %s",lanes.size(),running,discovery.summary());
        redistribute();
    }

    private double perLaneLimit(){return Math.max(0.0001,ceiling*Math.max(0.02,userScale)/Math.max(1,lanes.size()));}
    private void redistribute(){double each=perLaneLimit();for(Lane l:lanes)l.controller.setMaximumGain(each);}
    private float[] copyRing(){float[] out=new float[ringCount];int start=ringPos-ringCount;if(start<0)start+=ring.length;for(int i=0;i<ringCount;i++)out[i]=ring[(start+i)%ring.length];return out;}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    private final class Lane {
        final double anchorHz;double targetHz,lastDbFs=-120,lastProminenceDb=0;long lastStrongMs,referenceEpoch;boolean seen;final String label;final AutoController controller=new AutoController();final Osc osc=new Osc();
        Lane(double f,long now){anchorHz=f;targetHz=f;lastStrongMs=now;referenceEpoch=analysisSamples;label=String.format(Locale.US,"Headphone %.1f Hz",f);osc.frequency=f;osc.targetFrequency=f;}
    }
    private static final class Osc {double phase=0,frequency=0,targetFrequency=0,real=0,imag=0;}
}
