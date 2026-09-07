package com.p38.anclab.dsp;

import com.p38.anclab.profile.MechanicalFrequency;
import com.p38.anclab.telemetry.VehicleTelemetryRuntime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Coherent narrowband vehicle cancellation bank with live GPS/OBD order tracking and a
 * microphone-only fallback.
 *
 * With usable telemetry, each configured mechanical lane keeps its controller and oscillator while
 * its telemetry-predicted frequency moves. Around every prediction the selected cabin microphone
 * is searched within +/-1.2 Hz and the quiet/agile tracker refines the acoustic lock.
 *
 * If no configured telemetry lane can currently be predicted, the bank automatically scans the
 * selected microphone for persistent 8-200 Hz spectral lines. A candidate must survive independent
 * scans before it is allowed to become a cancellation lane. These discovered lanes are therefore
 * narrow, conservative and self-expiring rather than speculative broadband cancellation. Once
 * telemetry becomes usable again the fallback lanes are retired and the physical models resume.
 *
 * Speculative broadband remains outside this class and receives notched microphone audio. Both
 * telemetry-owned and fallback-discovered frequencies are returned by frequenciesHz(), so the
 * broadband path cannot compete with whichever narrowband controller currently owns a tone.
 */
public final class VehicleNarrowbandBank {
    private static final int SAMPLE_RATE=48000;
    private static final int DECIMATION=96;
    private static final double ANALYSIS_RATE=SAMPLE_RATE/(double)DECIMATION;
    private static final int RING_SAMPLES=1100;
    private static final int ANALYSIS_INTERVAL_SAMPLES=50; // 100 ms
    private static final double TWO_PI=Math.PI*2.0;
    private static final double SEARCH_HALF_WIDTH_HZ=1.20;

    private static final int DISCOVERY_SCAN_EVERY_ANALYSES=5; // ~2 scans/s
    private static final int MAX_DISCOVERED_LANES=6;
    private static final double DISCOVERY_SEARCH_HALF_WIDTH_HZ=0.85;
    private static final double DISCOVERY_MIN_SEPARATION_HZ=1.25;
    private static final double DISCOVERY_DUPLICATE_RADIUS_HZ=1.0;
    private static final long DISCOVERY_INACTIVE_STALE_MS=8000;

    private final ButterworthLowPass analysisLowPass=new ButterworthLowPass(SAMPLE_RATE,210.0);
    private final DcBlocker analysisDc=new DcBlocker(Math.exp(-2.0*Math.PI*3.0/SAMPLE_RATE));
    private final float[] ring=new float[RING_SAMPLES];
    private final VehicleTelemetryRuntime telemetry;
    private final BroadbandDetector discoveryDetector=new BroadbandDetector();
    private final List<DiscoveredLane> discovered=new ArrayList<>();

    private int ringPos=0,ringCount=0,decimator=0,sinceAnalysis=0,discoveryAnalysisCounter=0;
    private long totalAnalysisSamples=0;
    private Lane[] lanes=new Lane[0];
    private float totalCeiling=0.08f;
    private float userScale=0.50f;
    private float outRms=0f;
    private volatile String status="Predictable lanes ready";
    private volatile long frequencyRevision=0L;
    private boolean fallbackMode=false;

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
        for(DiscoveredLane l:discovered)if(Double.isFinite(l.currentFrequencyHz)&&l.currentFrequencyHz>=8&&l.currentFrequencyHz<=200)out.add(l.currentFrequencyHz);
        double[] f=new double[out.size()];for(int i=0;i<f.length;i++)f[i]=out.get(i);return f;
    }
    public long frequencyRevision(){return frequencyRevision;}
    public synchronized void setUserOutputScale(float scale){userScale=clamp(scale,0f,1f);redistributeLimits();}
    public float outputRms(){return(float)Math.sqrt(Math.max(0f,outRms));}
    public String status(){return status;}
    public synchronized int activeLaneCount(){int n=0;for(Lane l:lanes)if(l.available&&l.controller.output().gain()>1e-5)n++;for(DiscoveredLane l:discovered)if(l.controller.output().gain()>1e-5)n++;return n;}

    private void rebuild(List<MechanicalFrequency> requested,long now){
        if(requested==null)requested=List.of();
        List<Lane> clean=new ArrayList<>();
        for(MechanicalFrequency m:requested){
            if(m==null||!m.enabled())continue;
            double predicted=telemetry==null?Double.NaN:telemetry.predictedHz(m);
            double initial=Double.isFinite(predicted)?predicted:m.frequencyHz();
            if(initial<8.0||initial>200.0)continue;
            boolean duplicate=false;for(Lane existing:clean)if(Math.abs(existing.currentFrequencyHz-initial)<0.35){duplicate=true;break;}
            if(duplicate)continue;
            Lane lane=new Lane(m,initial);
            lane.available=Double.isFinite(predicted)&&predicted>=8.0&&predicted<=200.0;
            clean.add(lane);
        }
        lanes=clean.toArray(new Lane[0]);
        clearDiscovered(false);
        for(Lane l:lanes){
            l.referenceEpoch=totalAnalysisSamples;l.referencePhase=l.oscillator.phase;l.tracker.reset(l.currentFrequencyHz,now);
            if(l.available)l.controller.startTracking(now,perLaneLimit(),l.currentFrequencyHz,l.label,true);else l.controller.stop();
        }
        redistributeLimits();
        frequencyRevision++;
        status=lanes.length==0?"No configured telemetry lanes · narrow-line discovery ready":"Predictable narrowband · waiting for live telemetry";
    }

    private synchronized int effectiveLaneCount(){
        int n=discovered.size();for(Lane l:lanes)if(l.available)n++;return Math.max(1,n);
    }
    private synchronized void redistributeLimits(){double each=perLaneLimit();for(Lane l:lanes)l.controller.setMaximumGain(each);for(DiscoveredLane l:discovered)l.controller.setMaximumGain(each);}
    private synchronized double perLaneLimit(){return Math.max(0.0001,totalCeiling*Math.max(0.02f,userScale)/effectiveLaneCount());}

    /** Returns model-domain narrowband anti-noise for one 48 kHz microphone sample. */
    public float process(float microphone){
        if(telemetry!=null)telemetry.touch();
        double filtered=analysisLowPass.process(analysisDc.process(microphone));
        if(++decimator>=DECIMATION){decimator=0;ring[ringPos]=(float)filtered;if(++ringPos==ring.length)ringPos=0;if(ringCount<ring.length)ringCount++;totalAnalysisSamples++;if(++sinceAnalysis>=ANALYSIS_INTERVAL_SAMPLES){sinceAnalysis=0;analyze(System.currentTimeMillis());}}

        double value=0.0;
        double coefficientSmoothing=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.030));
        double frequencySmoothing=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.025));
        Lane[] telemetryLanes=lanes;
        for(Lane l:telemetryLanes){
            AutoController.Output o=l.controller.output();
            double targetGain=l.available?o.gain():0.0;
            value+=advanceOscillator(l.oscillator,l.available?o.frequencyHz():l.oscillator.frequencyHz,
                    targetGain,o.phaseRadians(),coefficientSmoothing,frequencySmoothing);
        }
        synchronized(this){
            for(DiscoveredLane l:discovered){
                AutoController.Output o=l.controller.output();
                value+=advanceOscillator(l.oscillator,o.frequencyHz(),o.gain(),o.phaseRadians(),coefficientSmoothing,frequencySmoothing);
            }
        }
        float ceiling=totalCeiling*userScale;
        float out=clamp((float)value,-ceiling,ceiling);
        outRms=0.995f*outRms+0.005f*out*out;
        return out;
    }

    private static double advanceOscillator(Oscillator oscillator,double targetFrequency,double targetGain,double targetPhase,
                                            double coefficientSmoothing,double frequencySmoothing){
        oscillator.targetFrequencyHz=targetFrequency;
        oscillator.targetReal=targetGain*Math.cos(targetPhase);
        oscillator.targetImag=targetGain*Math.sin(targetPhase);
        oscillator.frequencyHz+=frequencySmoothing*(oscillator.targetFrequencyHz-oscillator.frequencyHz);
        oscillator.real+=coefficientSmoothing*(oscillator.targetReal-oscillator.real);
        oscillator.imag+=coefficientSmoothing*(oscillator.targetImag-oscillator.imag);
        double value=oscillator.real*Math.cos(oscillator.phase)-oscillator.imag*Math.sin(oscillator.phase);
        oscillator.phase+=TWO_PI*oscillator.frequencyHz/SAMPLE_RATE;if(oscillator.phase>=TWO_PI)oscillator.phase-=TWO_PI;
        return value;
    }

    private void analyze(long now){
        if(ringCount<Math.min(300,RING_SAMPLES))return;
        float[] window=copyRing();long first=totalAnalysisSamples-window.length;
        int available=0,cancelling=0,learning=0;
        boolean moved=false,availabilityChanged=false;

        for(Lane l:lanes){
            double predicted=telemetry==null?Double.NaN:telemetry.predictedHz(l.model);
            boolean valid=Double.isFinite(predicted)&&predicted>=8.0&&predicted<=200.0;
            if(!valid){
                if(l.available){l.available=false;l.controller.stop();moved=true;availabilityChanged=true;}
                continue;
            }
            available++;
            if(!l.available){
                l.available=true;l.currentFrequencyHz=predicted;l.tracker.reset(predicted,now);
                l.referenceEpoch=totalAnalysisSamples;l.referencePhase=l.oscillator.phase;
                l.controller.startTracking(now,perLaneLimit(),predicted,l.label,true);moved=true;availabilityChanged=true;
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

        // No usable physical-model lane: revert automatically to the recovered v0.5-style
        // persistent narrow-line discovery path rather than doing nothing.
        if(available==0){
            if(!fallbackMode){fallbackMode=true;discoveryAnalysisCounter=DISCOVERY_SCAN_EVERY_ANALYSES;discoveryDetector.reset();}
            DiscoveryResult r=analyzeFallbackDiscovery(window,first,now);
            moved|=r.moved;cancelling+=r.cancelling;
        }else if(fallbackMode||!discovered.isEmpty()){
            fallbackMode=false;clearDiscovered(true);discoveryDetector.reset();moved=true;availabilityChanged=true;
        }

        if(availabilityChanged)redistributeLimits();
        if(moved)frequencyRevision++;
        String telem=telemetry==null?"":telemetry.status();
        if(available==0){
            status=String.format(Locale.US,"No usable GPS/OBD mechanical input · auto-discovering stable 8–200 Hz lines · %d found · %d cancelling%s",
                    discovered.size(),cancelling,telem.isEmpty()?"":"\n"+telem);
        }else{
            status=String.format(Locale.US,"Predictable narrowband · %d/%d telemetry · %d cancelling · %d learning%s",
                    available,lanes.length,cancelling,learning,telem.isEmpty()?"":"\n"+telem);
        }
    }

    private synchronized DiscoveryResult analyzeFallbackDiscovery(float[] window,long first,long now){
        boolean moved=false,changed=false;int cancelling=0;

        if(++discoveryAnalysisCounter>=DISCOVERY_SCAN_EVERY_ANALYSES){
            discoveryAnalysisCounter=0;
            List<SpectrumAnalyzer.DetectedTone> peaks=SpectrumAnalyzer.findPeaks(window,first,ANALYSIS_RATE,
                    8.0,200.0,12,DISCOVERY_MIN_SEPARATION_HZ);
            List<BroadbandDetector.Candidate> ready=discoveryDetector.update(peaks,now);
            for(BroadbandDetector.Candidate candidate:ready){
                if(discovered.size()>=MAX_DISCOVERED_LANES)break;
                if(nearOwnedFrequency(candidate.frequencyHz()))continue;
                DiscoveredLane lane=new DiscoveredLane(candidate.id(),candidate.frequencyHz(),now);
                lane.referenceEpoch=totalAnalysisSamples;lane.referencePhase=lane.oscillator.phase;
                lane.tracker.reset(lane.currentFrequencyHz,now);
                discovered.add(lane);
                redistributeLimits();
                lane.controller.startTracking(now,perLaneLimit(),lane.currentFrequencyHz,lane.label,false);
                moved=true;changed=true;
            }
        }

        Iterator<DiscoveredLane> iterator=discovered.iterator();
        while(iterator.hasNext()){
            DiscoveredLane l=iterator.next();
            double centre=l.currentFrequencyHz;
            SpectrumSnapshot search=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,
                    Math.max(8.0,centre-DISCOVERY_SEARCH_HALF_WIDTH_HZ),
                    Math.min(200.0,centre+DISCOVERY_SEARCH_HALF_WIDTH_HZ),centre,l.referenceEpoch,l.referencePhase);
            boolean lock=search.peakDbFs()>-76.0&&search.contrastDb()>2.0&&Math.abs(search.peakFrequencyHz()-centre)<=DISCOVERY_SEARCH_HALF_WIDTH_HZ;
            double refined=centre;
            if(lock){
                refined=l.tracker.update(search.peakFrequencyHz(),now);
                l.lastStrongMs=now;
                refined=Math.max(8.0,Math.min(200.0,refined));
                if(Math.abs(refined-l.currentFrequencyHz)>0.025){l.currentFrequencyHz=refined;moved=true;}
                l.controller.followFrequency(refined,now);
            }

            SpectrumSnapshot exact=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,
                    Math.max(8.0,refined-0.15),Math.min(200.0,refined+0.15),refined,l.referenceEpoch,l.referencePhase);
            l.controller.update(exact,now);
            double gain=l.controller.output().gain();
            if(gain>1e-5)cancelling++;

            // If a candidate never becomes useful and the microphone line has disappeared, free
            // the lane. A lane already producing validated cancellation is left to AutoController's
            // own worse-result rollback rather than being removed merely because cancellation made
            // the residual tone quiet.
            if(gain<=1e-5&&now-l.lastStrongMs>DISCOVERY_INACTIVE_STALE_MS){
                l.controller.stop();iterator.remove();moved=true;changed=true;
            }
        }
        if(changed)redistributeLimits();
        return new DiscoveryResult(moved,cancelling);
    }

    private boolean nearOwnedFrequency(double hz){
        for(Lane l:lanes)if(l.available&&Math.abs(l.currentFrequencyHz-hz)<DISCOVERY_DUPLICATE_RADIUS_HZ)return true;
        for(DiscoveredLane l:discovered)if(Math.abs(l.currentFrequencyHz-hz)<DISCOVERY_DUPLICATE_RADIUS_HZ)return true;
        return false;
    }

    private synchronized void clearDiscovered(boolean redistribute){
        for(DiscoveredLane l:discovered)l.controller.stop();
        if(!discovered.isEmpty())frequencyRevision++;
        discovered.clear();
        if(redistribute)redistributeLimits();
    }

    private float[] copyRing(){float[] out=new float[ringCount];int start=ringPos-ringCount;if(start<0)start+=ring.length;for(int i=0;i<ringCount;i++)out[i]=ring[(start+i)%ring.length];return out;}

    private static final class Lane {
        final MechanicalFrequency model;final String label;final AutoController controller=new AutoController();final AdaptiveFrequencyTracker tracker=new AdaptiveFrequencyTracker();final Oscillator oscillator=new Oscillator();long referenceEpoch;double referencePhase;double currentFrequencyHz;boolean available;
        Lane(MechanicalFrequency model,double f){this.model=model;label=model.name();currentFrequencyHz=f;oscillator.frequencyHz=f;oscillator.targetFrequencyHz=f;}
    }
    private static final class DiscoveredLane {
        final String id,label;final AutoController controller=new AutoController();final AdaptiveFrequencyTracker tracker=new AdaptiveFrequencyTracker();final Oscillator oscillator=new Oscillator();long referenceEpoch;double referencePhase;double currentFrequencyHz;long lastStrongMs;
        DiscoveredLane(String id,double f,long now){this.id=id;label=String.format(Locale.US,"Auto %.1f Hz",f);currentFrequencyHz=f;lastStrongMs=now;oscillator.frequencyHz=f;oscillator.targetFrequencyHz=f;}
    }
    private record DiscoveryResult(boolean moved,int cancelling) { }
    private static final class Oscillator {double phase=0,frequencyHz=0,targetFrequencyHz=0,real=0,imag=0,targetReal=0,targetImag=0;}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
