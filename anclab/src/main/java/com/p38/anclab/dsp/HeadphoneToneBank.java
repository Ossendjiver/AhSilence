package com.p38.anclab.dsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Dedicated coherent-tone layer for headphones. Persistent microphone-discovered lines are given
 * bounded sinusoidal controllers while the predictive FxNLMS handles the notched residual. This
 * mirrors the vehicle narrowband architecture without requiring telemetry.
 */
public final class HeadphoneToneBank {
    private static final int SAMPLE_RATE=48000;
    private static final int DECIMATION=32;
    private static final double ANALYSIS_RATE=SAMPLE_RATE/(double)DECIMATION; // 1500 Hz
    private static final int RING_SAMPLES=4096;                               // 2.73 s
    private static final int ANALYSIS_INTERVAL=750;                           // 0.5 s
    private static final int MAX_LANES=4;
    private static final double MIN_HZ=15.0,MAX_HZ=600.0;
    private static final double MIN_PROMINENCE_DB=8.0;
    private static final double ABSOLUTE_SANITY_FLOOR_DBFS=-112.0;
    private static final long STALE_MS=6000;
    private static final double MATCH_HZ=1.25;
    private static final double TRACK_RADIUS_HZ=2.0;
    private static final double TONE_AUTHORITY_FRACTION=0.35;
    private static final double TWO_PI=Math.PI*2.0;

    private final HeadphoneBandLimiter analysisBand=new HeadphoneBandLimiter(SAMPLE_RATE,true);
    private final PredictableFrequencyDiscovery discovery=new PredictableFrequencyDiscovery(MIN_HZ,MAX_HZ);
    private final float[] ring=new float[RING_SAMPLES];
    private final List<Lane> lanes=new ArrayList<>();
    private int ringPos=0,ringCount=0,decimator=0,sinceAnalysis=0;
    private long inputSamples=0,analysisSamples=0;
    private float ceiling,userScale=0.5f,outRms=0f;
    private volatile String status="headphone tone discovery warming up";
    private volatile long revision=0;

    public HeadphoneToneBank(float safeOutputCeiling,float userScale){
        ceiling=clamp(Math.abs(safeOutputCeiling),0.005f,0.50f);this.userScale=clamp(userScale,0f,1f);
    }
    public synchronized void setUserOutputScale(float v){userScale=clamp(v,0f,1f);redistribute();}
    public float outputRms(){return(float)Math.sqrt(Math.max(0,outRms));}
    public String status(){return status;}
    public long frequencyRevision(){return revision;}
    public synchronized double[] frequenciesHz(){double[] f=new double[lanes.size()];for(int i=0;i<f.length;i++)f[i]=lanes.get(i).controller.output().frequencyHz();return f;}
    public List<BroadbandDetector.Candidate> candidates(){return discovery.candidates();}
    public synchronized int activeLaneCount(){int n=0;for(Lane l:lanes)if(l.controller.output().gain()>1e-6)n++;return n;}

    /** Model-domain anti-noise for one microphone sample. */
    public float process(float microphone){
        float filtered=analysisBand.process(microphone);inputSamples++;discovery.observe(filtered);
        if(++decimator>=DECIMATION){
            decimator=0;ring[ringPos]=filtered;if(++ringPos==ring.length)ringPos=0;if(ringCount<ring.length)ringCount++;analysisSamples++;
            if(++sinceAnalysis>=ANALYSIS_INTERVAL){sinceAnalysis=0;analyze(Math.round(inputSamples*1000.0/SAMPLE_RATE));}
        }
        double value=0;double coeffSmooth=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.030)),freqSmooth=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.025));
        synchronized(this){for(Lane l:lanes){AutoController.Output o=l.controller.output();l.osc.targetFrequency=o.frequencyHz();l.osc.frequency+=freqSmooth*(l.osc.targetFrequency-l.osc.frequency);double tr=o.gain()*Math.cos(o.phaseRadians()),ti=o.gain()*Math.sin(o.phaseRadians());l.osc.real+=coeffSmooth*(tr-l.osc.real);l.osc.imag+=coeffSmooth*(ti-l.osc.imag);value+=l.osc.real*Math.cos(l.osc.phase)-l.osc.imag*Math.sin(l.osc.phase);l.osc.phase+=TWO_PI*l.osc.frequency/SAMPLE_RATE;if(l.osc.phase>=TWO_PI)l.osc.phase-=TWO_PI;}}
        float bankCeiling=(float)(ceiling*userScale*TONE_AUTHORITY_FRACTION);
        float out=clamp((float)value,-bankCeiling,bankCeiling);outRms=0.995f*outRms+0.005f*out*out;return out;
    }

    private synchronized void analyze(long now){
        if(ringCount<RING_SAMPLES)return;
        List<BroadbandDetector.Candidate> candidates=new ArrayList<>(discovery.candidates());
        candidates.removeIf(c->c.dbFs()<ABSOLUTE_SANITY_FLOOR_DBFS||c.prominenceDb()<MIN_PROMINENCE_DB);
        candidates.sort(Comparator.comparingDouble(BroadbandDetector.Candidate::score).reversed());
        for(Lane l:lanes)l.seen=false;
        for(BroadbandDetector.Candidate c:candidates){
            Lane best=null;double dBest=Double.POSITIVE_INFINITY;
            for(Lane l:lanes){double d=Math.abs(l.frequencyHz-c.frequencyHz());if(d<MATCH_HZ&&d<dBest){best=l;dBest=d;}}
            if(best!=null){best.seen=true;best.lastStrongMs=now;best.lastDbFs=c.dbFs();best.lastProminenceDb=c.prominenceDb();best.targetHz=c.frequencyHz();continue;}
            if(lanes.size()>=MAX_LANES){
                Lane weakest=null;for(Lane l:lanes)if("IDLE".equals(l.controller.stageName())&&l.controller.output().gain()<=1e-6&&(weakest==null||l.discoveryScore<weakest.discoveryScore))weakest=l;
                if(weakest==null||c.score()<weakest.discoveryScore*1.35)continue;
                weakest.controller.stop();lanes.remove(weakest);
            }
            Lane l=new Lane(c.frequencyHz(),c.score(),now);l.lastDbFs=c.dbFs();l.lastProminenceDb=c.prominenceDb();l.seen=true;lanes.add(l);redistribute();l.controller.startTracking(now,perLaneLimit(),l.targetHz,l.label,false);revision++;
        }
        float[] window=copyRing();long first=analysisSamples-window.length;int running=0;
        Iterator<Lane> it=lanes.iterator();
        while(it.hasNext()){
            Lane l=it.next();
            if(l.seen){double bounded=Math.max(l.anchorHz-TRACK_RADIUS_HZ,Math.min(l.anchorHz+TRACK_RADIUS_HZ,l.targetHz));if(Math.abs(bounded-l.frequencyHz)>0.02)l.frequencyHz=l.tracker.update(bounded,now);l.controller.followFrequency(l.frequencyHz,now);}
            if("IDLE".equals(l.controller.stageName())){if(l.idleSinceMs==0)l.idleSinceMs=now;if(now-l.idleSinceMs>=FrequencyLanePolicy.CONTROLLER_RETRY_DELAY_MS){l.controller.startTracking(now,perLaneLimit(),l.frequencyHz,l.label,false);l.idleSinceMs=0;}}else l.idleSinceMs=0;
            double controlled=l.controller.output().frequencyHz();
            SpectrumSnapshot exact=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,Math.max(MIN_HZ,controlled-0.22),Math.min(MAX_HZ,controlled+0.22),controlled,l.referenceEpoch,l.referencePhase);
            l.controller.update(exact,now);
            if(l.controller.output().gain()>1e-5)running++;
            if(!l.seen&&l.controller.output().gain()<=1e-5&&now-l.lastStrongMs>STALE_MS){l.controller.stop();it.remove();revision++;}
        }
        mergeCollisions();redistribute();
        if(lanes.isEmpty())status="no mature predictable tones · predictive residual only";
        else status=String.format(Locale.US,"%d tone lanes · %d active · %s",lanes.size(),running,discovery.summary());
    }

    private void mergeCollisions(){for(int i=0;i<lanes.size();i++){Lane a=lanes.get(i);for(int j=i+1;j<lanes.size();){Lane b=lanes.get(j);if(Math.abs(a.frequencyHz-b.frequencyHz)<0.8){Lane drop=a.discoveryScore>=b.discoveryScore?b:a;drop.controller.stop();lanes.remove(drop);revision++;if(drop==a){i--;break;}}else j++;}}}
    private double perLaneLimit(){return Math.max(0.00005,ceiling*Math.max(0.01,userScale)*TONE_AUTHORITY_FRACTION/Math.max(1,lanes.size()));}
    private void redistribute(){double each=perLaneLimit();for(Lane l:lanes)l.controller.setMaximumGain(each);}
    private float[] copyRing(){float[] out=new float[ringCount];int start=ringPos-ringCount;if(start<0)start+=ring.length;for(int i=0;i<ringCount;i++)out[i]=ring[(start+i)%ring.length];return out;}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    private final class Lane {
        final double anchorHz,discoveryScore;double targetHz,frequencyHz,lastDbFs=-120,lastProminenceDb=0;long lastStrongMs,referenceEpoch,idleSinceMs;double referencePhase;boolean seen;final String label;final AutoController controller=new AutoController();final AdaptiveFrequencyTracker tracker=new AdaptiveFrequencyTracker();final Osc osc=new Osc();
        Lane(double f,double score,long now){anchorHz=f;targetHz=frequencyHz=f;discoveryScore=score;lastStrongMs=now;referenceEpoch=analysisSamples;referencePhase=0;label=String.format(Locale.US,"Headphone %.1f Hz",f);osc.frequency=f;osc.targetFrequency=f;tracker.setBounds(Math.max(MIN_HZ,f-TRACK_RADIUS_HZ),Math.min(MAX_HZ,f+TRACK_RADIUS_HZ));tracker.reset(f,now);}
    }
    private static final class Osc {double phase=0,frequency=0,targetFrequency=0,real=0,imag=0;}
}
