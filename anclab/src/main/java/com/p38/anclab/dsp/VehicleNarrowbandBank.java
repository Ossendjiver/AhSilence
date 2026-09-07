package com.p38.anclab.dsp;

import com.p38.anclab.profile.MechanicalFrequency;
import com.p38.anclab.profile.VehicleCancellationRecipe;
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
 * narrow, conservative and self-expiring rather than speculative broadband cancellation. Each
 * fallback lane is anchored to the physical neighbourhood in which it was admitted, and lanes that
 * later converge onto the same physical tone are coalesced instead of competing for it.
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
    private static final double DISCOVERY_TRACK_HALF_WIDTH_HZ=0.70;
    private static final double DISCOVERY_MIN_SEPARATION_HZ=1.25;
    private static final double DISCOVERY_DUPLICATE_RADIUS_HZ=1.0;
    private static final double DISCOVERY_COLLISION_RADIUS_HZ=0.85;
    private static final long DISCOVERY_INACTIVE_STALE_MS=8000;
    private static final int RECIPE_SUCCESS_UPDATES=12;
    private static final long RECIPE_SAVE_INTERVAL_MS=30000;

    private final ButterworthLowPass analysisLowPass=new ButterworthLowPass(SAMPLE_RATE,210.0);
    private final DcBlocker analysisDc=new DcBlocker(Math.exp(-2.0*Math.PI*3.0/SAMPLE_RATE));
    private final float[] ring=new float[RING_SAMPLES];
    private final VehicleTelemetryRuntime telemetry;
    private final BroadbandDetector discoveryDetector=new BroadbandDetector();
    private final List<DiscoveredLane> discovered=new ArrayList<>();
    private final List<VehicleCancellationRecipe> pendingRecipes=new ArrayList<>();
    private final VehicleRecipeBook recipeBook;
    private final String routeKey;
    private final double cancellationMinimumHz;
    private final double cancellationMaximumHz;

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
        this(models,telemetry,safeOutputCeiling,userScale,
                FrequencyLanePolicy.DEFAULT_CANCELLATION_MINIMUM_HZ,
                FrequencyLanePolicy.MONITOR_MAXIMUM_HZ,"",List.of());
    }

    public VehicleNarrowbandBank(List<MechanicalFrequency> models,VehicleTelemetryRuntime telemetry,
                                 float safeOutputCeiling,float userScale,
                                 double cancellationMinimumHz,double cancellationMaximumHz,
                                 String routeKey,List<VehicleCancellationRecipe> recipes){
        this.telemetry=telemetry;
        totalCeiling=clamp(Math.abs(safeOutputCeiling),0.005f,0.15f);
        this.userScale=clamp(userScale,0f,1f);
        this.cancellationMinimumHz=Math.max(FrequencyLanePolicy.MONITOR_MINIMUM_HZ,
                Math.min(FrequencyLanePolicy.MONITOR_MAXIMUM_HZ,cancellationMinimumHz));
        this.cancellationMaximumHz=Math.max(this.cancellationMinimumHz,
                Math.min(FrequencyLanePolicy.MONITOR_MAXIMUM_HZ,cancellationMaximumHz));
        this.routeKey=routeKey==null?"":routeKey;
        recipeBook=new VehicleRecipeBook(recipes);
        rebuild(models,System.currentTimeMillis());
    }

    public synchronized double[] frequenciesHz(){
        List<Double> out=new ArrayList<>();
        for(Lane l:lanes){
            if(!l.available)continue;
            double f=l.controller.output().frequencyHz();
            if(Double.isFinite(f)&&f>=8&&f<=200)out.add(f);
        }
        for(DiscoveredLane l:discovered){
            double f=l.controller.output().frequencyHz();
            if(Double.isFinite(f)&&f>=8&&f<=200)out.add(f);
        }
        double[] f=new double[out.size()];for(int i=0;i<f.length;i++)f[i]=out.get(i);return f;
    }
    public long frequencyRevision(){return frequencyRevision;}
    public synchronized void setUserOutputScale(float scale){userScale=clamp(scale,0f,1f);redistributeLimits();}
    public float outputRms(){return(float)Math.sqrt(Math.max(0f,outRms));}
    public String status(){return status;}
    public synchronized int activeLaneCount(){int n=0;for(Lane l:lanes)if(l.available&&l.controller.output().gain()>1e-5)n++;for(DiscoveredLane l:discovered)if(l.controller.output().gain()>1e-5)n++;return n;}
    public synchronized List<VehicleCancellationRecipe> drainLearnedRecipes(){List<VehicleCancellationRecipe> out=List.copyOf(pendingRecipes);pendingRecipes.clear();return out;}

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
            lane.cancellable=FrequencyLanePolicy.cancellable(initial,cancellationMinimumHz,cancellationMaximumHz);
            clean.add(lane);
        }
        lanes=clean.toArray(new Lane[0]);
        clearDiscovered(false);
        for(Lane l:lanes){
            l.referenceEpoch=totalAnalysisSamples;l.referencePhase=l.oscillator.phase;l.tracker.reset(l.currentFrequencyHz,now);
            if(l.available&&l.cancellable){startTelemetryController(l,now);l.controllerEnabled=true;}else l.controller.stop();
        }
        redistributeLimits();
        frequencyRevision++;
        status=lanes.length==0?"No configured telemetry lanes · narrow-line discovery ready":"Predictable narrowband · waiting for live telemetry";
        publishLaneRegistry();
    }

    private synchronized int effectiveLaneCount(){
        int n=0;for(DiscoveredLane l:discovered)if(l.cancellable)n++;
        for(Lane l:lanes)if(l.controllerEnabled)n++;return Math.max(1,n);
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
            double targetGain=l.controllerEnabled?o.gain():0.0;
            value+=advanceOscillator(l.oscillator,l.controllerEnabled?o.frequencyHz():l.oscillator.frequencyHz,
                    targetGain,o.phaseRadians(),coefficientSmoothing,frequencySmoothing);
        }
        synchronized(this){
            for(DiscoveredLane l:discovered){
                AutoController.Output o=l.controller.output();
                value+=advanceOscillator(l.oscillator,o.frequencyHz(),l.cancellable?o.gain():0.0,
                        o.phaseRadians(),coefficientSmoothing,frequencySmoothing);
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
        int available=0,controllable=0,cancelling=0,learning=0,monitorOnly=0;
        boolean moved=false,availabilityChanged=false;

        // Resolve telemetry ownership before updating controllers. A later model that converges on
        // an earlier model remains visible but muted until the orders diverge again.
        for(Lane l:lanes){
            double predicted=telemetry==null?Double.NaN:telemetry.predictedHz(l.model);
            boolean valid=Double.isFinite(predicted)&&predicted>=8.0&&predicted<=200.0;
            l.predictedFrequencyHz=predicted;l.available=valid;l.suppressed=false;
            l.cancellable=valid&&FrequencyLanePolicy.cancellable(predicted,cancellationMinimumHz,cancellationMaximumHz);
        }
        for(int i=0;i<lanes.length;i++){
            Lane lane=lanes[i];if(!lane.available||!lane.cancellable)continue;
            for(int j=0;j<i;j++){
                Lane owner=lanes[j];
                if(owner.available&&owner.cancellable&&!owner.suppressed
                        &&(FrequencyLanePolicy.collide(owner.predictedFrequencyHz,lane.predictedFrequencyHz)
                        ||FrequencyLanePolicy.collide(owner.currentFrequencyHz,lane.currentFrequencyHz))){
                    lane.suppressed=true;break;
                }
            }
        }

        for(Lane l:lanes){
            boolean shouldRun=l.available&&l.cancellable&&!l.suppressed;
            if(shouldRun&&!l.controllerEnabled){
                l.currentFrequencyHz=l.predictedFrequencyHz;l.tracker.reset(l.currentFrequencyHz,now);
                l.referenceEpoch=totalAnalysisSamples;l.referencePhase=l.oscillator.phase;
                startTelemetryController(l,now);l.controllerEnabled=true;moved=true;availabilityChanged=true;
            }else if(!shouldRun&&l.controllerEnabled){
                l.controller.stop();l.controllerEnabled=false;l.successUpdates=0;moved=true;availabilityChanged=true;
            }
            if(!l.available)continue;
            available++;if(shouldRun)controllable++;else monitorOnly++;

            double predicted=l.predictedFrequencyHz;
            double lo=Math.max(8.0,predicted-SEARCH_HALF_WIDTH_HZ),hi=Math.min(200.0,predicted+SEARCH_HALF_WIDTH_HZ);
            SpectrumSnapshot search=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,lo,hi,predicted,l.referenceEpoch,l.referencePhase);
            boolean acousticLock=search.peakDbFs()>-78.0&&search.contrastDb()>2.2&&Math.abs(search.peakFrequencyHz()-predicted)<=SEARCH_HALF_WIDTH_HZ;
            double refined=predicted;
            double trackingRadius=FrequencyLanePolicy.telemetryTrackingRadius(predicted);
            l.tracker.setBounds(Math.max(8.0,predicted-trackingRadius),
                    Math.min(200.0,predicted+trackingRadius));
            if(acousticLock){
                refined=l.tracker.update(search.peakFrequencyHz(),now);
                if(telemetry!=null){telemetry.observe(l.model,search.peakFrequencyHz(),search.contrastDb(),search.peakDbFs());learning++;}
            }else{
                double estimate=l.tracker.estimateHz();
                if(Double.isFinite(estimate)&&Math.abs(estimate-predicted)<SEARCH_HALF_WIDTH_HZ)refined=0.85*estimate+0.15*predicted;
            }
            refined=Math.max(8.0,Math.min(200.0,refined));
            if(Math.abs(refined-l.currentFrequencyHz)>0.025){l.currentFrequencyHz=refined;moved=true;}
            if(shouldRun&&collidesWithEarlierTelemetryLane(l)){
                l.suppressed=true;l.controller.stop();l.controllerEnabled=false;l.successUpdates=0;
                shouldRun=false;controllable--;monitorOnly++;availabilityChanged=true;moved=true;
            }
            if(!shouldRun)continue;
            if("IDLE".equals(l.controller.stageName())){
                if(l.idleSinceMs==0)l.idleSinceMs=now;
                if(FrequencyLanePolicy.controllerRetryDue(l.controller.stageName(),l.idleSinceMs,now))
                    startTelemetryController(l,now);
            }else l.idleSinceMs=0;
            l.controller.followFrequency(refined,now);

            double controlled=l.controller.output().frequencyHz();
            SpectrumSnapshot exact=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,
                    Math.max(8.0,controlled-0.15),Math.min(200.0,controlled+0.15),controlled,l.referenceEpoch,l.referencePhase);
            l.controller.update(exact,now);
            if(l.controller.output().gain()>1e-5)cancelling++;
            maybeCaptureRecipe(l,now);
        }

        if(controllable==0){
            if(!fallbackMode){fallbackMode=true;discoveryAnalysisCounter=DISCOVERY_SCAN_EVERY_ANALYSES;discoveryDetector.reset();}
            DiscoveryResult r=analyzeFallbackDiscovery(window,first,now);
            moved|=r.moved;cancelling+=r.cancelling;
        }else if(fallbackMode||!discovered.isEmpty()){
            fallbackMode=false;clearDiscovered(true);discoveryDetector.reset();moved=true;availabilityChanged=true;
        }

        if(availabilityChanged)redistributeLimits();
        if(moved)frequencyRevision++;
        String telem=telemetry==null?"":telemetry.status();
        if(controllable==0){
            status=String.format(Locale.US,"No cancellable GPS/OBD lane · auto-discovering stable 8–200 Hz lines · %d found · %d cancelling · %d monitor-only%s",
                    discovered.size(),cancelling,monitorOnly,telem.isEmpty()?"":"\n"+telem);
        }else{
            status=String.format(Locale.US,"Predictable narrowband · %d/%d telemetry · %d controllable · %d cancelling · %d monitor-only · %d learning%s",
                    available,lanes.length,controllable,cancelling,monitorOnly,learning,telem.isEmpty()?"":"\n"+telem);
        }
        publishLaneRegistry();
    }

    private boolean collidesWithEarlierTelemetryLane(Lane candidate){
        for(Lane owner:lanes){
            if(owner==candidate)break;
            if(owner.controllerEnabled&&!owner.suppressed
                    &&FrequencyLanePolicy.collide(owner.currentFrequencyHz,candidate.currentFrequencyHz))return true;
        }
        return false;
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
                lane.cancellable=FrequencyLanePolicy.cancellable(lane.currentFrequencyHz,
                        cancellationMinimumHz,cancellationMaximumHz);
                discovered.add(lane);
                redistributeLimits();
                if(lane.cancellable)startDiscoveredController(lane,now);else lane.controller.stop();
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
                if(l.cancellable)l.controller.followFrequency(refined,now);
            }

            double gain=0.0;
            if(l.cancellable){
                if("IDLE".equals(l.controller.stageName())){
                    if(l.idleSinceMs==0)l.idleSinceMs=now;
                    if(FrequencyLanePolicy.controllerRetryDue(l.controller.stageName(),l.idleSinceMs,now))
                        startDiscoveredController(l,now);
                }else l.idleSinceMs=0;
                double controlled=l.controller.output().frequencyHz();
                SpectrumSnapshot exact=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,
                        Math.max(8.0,controlled-0.15),Math.min(200.0,controlled+0.15),controlled,l.referenceEpoch,l.referencePhase);
                l.controller.update(exact,now);gain=l.controller.output().gain();maybeCaptureRecipe(l,now);
            }
            if(gain>1e-5)cancelling++;

            if(gain<=1e-5&&now-l.lastStrongMs>DISCOVERY_INACTIVE_STALE_MS){
                l.controller.stop();iterator.remove();moved=true;changed=true;
            }
        }

        if(mergeDiscoveredCollisions()){moved=true;changed=true;}
        if(changed){
            redistributeLimits();
            cancelling=0;for(DiscoveredLane l:discovered)if(l.controller.output().gain()>1e-5)cancelling++;
        }
        return new DiscoveryResult(moved,cancelling);
    }

    /** Keep the older lane when independent admissions converge onto one physical tone. */
    private boolean mergeDiscoveredCollisions(){
        boolean merged=false;
        for(int i=0;i<discovered.size();i++){
            DiscoveredLane keep=discovered.get(i);
            for(int j=i+1;j<discovered.size();){
                DiscoveredLane other=discovered.get(j);
                double keepHz=keep.controller.output().frequencyHz();
                double otherHz=other.controller.output().frequencyHz();
                if(Math.abs(keepHz-otherHz)<DISCOVERY_COLLISION_RADIUS_HZ){
                    other.controller.stop();
                    discovered.remove(j);
                    merged=true;
                }else j++;
            }
        }
        return merged;
    }

    private void startTelemetryController(Lane lane,long now){
        double source=telemetry==null?Double.NaN:telemetry.sourceValue(lane.model);
        VehicleCancellationRecipe recipe=recipeBook.find(routeKey,lane.model.id(),
                lane.model.detectedNumberType(),source,lane.currentFrequencyHz);
        if(recipe==null)lane.controller.startTracking(now,perLaneLimit(),lane.currentFrequencyHz,lane.label,true);
        else lane.controller.startTrackingWithSecondaryPath(now,perLaneLimit(),lane.currentFrequencyHz,
                lane.label,true,recipe.secondaryPath());
        lane.idleSinceMs=0;lane.successUpdates=0;
    }

    private void startDiscoveredController(DiscoveredLane lane,long now){
        VehicleCancellationRecipe recipe=recipeBook.find(routeKey,"discovered",
                MechanicalFrequency.SourceType.FIXED,lane.anchorFrequencyHz,lane.currentFrequencyHz);
        if(recipe==null)lane.controller.startTracking(now,perLaneLimit(),lane.currentFrequencyHz,lane.label,false);
        else lane.controller.startTrackingWithSecondaryPath(now,perLaneLimit(),lane.currentFrequencyHz,
                lane.label,false,recipe.secondaryPath());
        lane.idleSinceMs=0;lane.successUpdates=0;
    }

    private void maybeCaptureRecipe(Lane lane,long now){
        if(!successful(lane.controller)){lane.successUpdates=0;return;}
        if(++lane.successUpdates<RECIPE_SUCCESS_UPDATES||now-lane.lastRecipeMs<RECIPE_SAVE_INTERVAL_MS)return;
        double source=telemetry==null?Double.NaN:telemetry.sourceValue(lane.model);
        captureRecipe(lane.model.id(),lane.model.detectedNumberType(),source,lane.controller,lane.currentFrequencyHz);
        lane.successUpdates=0;lane.lastRecipeMs=now;
    }

    private void maybeCaptureRecipe(DiscoveredLane lane,long now){
        if(!successful(lane.controller)){lane.successUpdates=0;return;}
        if(++lane.successUpdates<RECIPE_SUCCESS_UPDATES||now-lane.lastRecipeMs<RECIPE_SAVE_INTERVAL_MS)return;
        captureRecipe("discovered",MechanicalFrequency.SourceType.FIXED,lane.anchorFrequencyHz,
                lane.controller,lane.currentFrequencyHz);
        lane.successUpdates=0;lane.lastRecipeMs=now;
    }

    private void captureRecipe(String modelId,MechanicalFrequency.SourceType sourceType,double source,
                               AutoController controller,double frequency){
        Complex path=controller.secondaryPathEstimate();double improvement=controller.currentImprovementDb();
        if(path.magnitude()<1.0e-5||!Double.isFinite(improvement)||improvement<1.0)return;
        double sourceBin=VehicleCancellationRecipe.quantizeSource(sourceType,source,frequency);
        VehicleCancellationRecipe observation=new VehicleCancellationRecipe(routeKey,modelId,sourceType,
                sourceBin,frequency,path.re(),path.im(),improvement,1,System.currentTimeMillis());
        recipeBook.add(observation);pendingRecipes.add(observation);
    }

    private static boolean successful(AutoController controller){
        if(controller.output().gain()<=1.0e-5||!controller.hasUsableSecondaryPathEstimate()
                ||!Double.isFinite(controller.currentImprovementDb()))return false;
        String stage=controller.stageName();
        return "RUNNING".equals(stage)||"VERIFY_FINE".equals(stage)||"FOLLOW_VERIFY".equals(stage)
                ||stage.startsWith("SEEK");
    }

    private boolean nearOwnedFrequency(double hz){
        for(Lane l:lanes)if(l.available&&Math.abs(l.controller.output().frequencyHz()-hz)<DISCOVERY_DUPLICATE_RADIUS_HZ)return true;
        for(DiscoveredLane l:discovered)if(Math.abs(l.controller.output().frequencyHz()-hz)<DISCOVERY_DUPLICATE_RADIUS_HZ)return true;
        return false;
    }

    private synchronized void clearDiscovered(boolean redistribute){
        for(DiscoveredLane l:discovered)l.controller.stop();
        if(!discovered.isEmpty())frequencyRevision++;
        discovered.clear();
        if(redistribute)redistributeLimits();
        publishLaneRegistry();
    }

    private synchronized void publishLaneRegistry(){
        List<VehicleLaneRegistry.Lane> state=new ArrayList<>();
        for(Lane l:lanes){
            if(!l.available)continue;
            AutoController.Output o=l.controller.output();
            state.add(new VehicleLaneRegistry.Lane(l.model.id(),l.label,o.frequencyHz(),o.gain(),
                    Math.toDegrees(o.phaseRadians()),l.controller.stageName(),o.status(),
                    l.controller.currentImprovementDb(),false,!l.cancellable||l.suppressed));
        }
        for(DiscoveredLane l:discovered){
            AutoController.Output o=l.controller.output();
            state.add(new VehicleLaneRegistry.Lane(l.id,l.label,o.frequencyHz(),o.gain(),
                    Math.toDegrees(o.phaseRadians()),l.controller.stageName(),o.status(),
                    l.controller.currentImprovementDb(),true,!l.cancellable));
        }
        VehicleLaneRegistry.publish(state);
    }

    private float[] copyRing(){float[] out=new float[ringCount];int start=ringPos-ringCount;if(start<0)start+=ring.length;for(int i=0;i<ringCount;i++)out[i]=ring[(start+i)%ring.length];return out;}

    private static final class Lane {
        final MechanicalFrequency model;final String label;final AutoController controller=new AutoController();final AdaptiveFrequencyTracker tracker=new AdaptiveFrequencyTracker();final Oscillator oscillator=new Oscillator();long referenceEpoch;double referencePhase;double currentFrequencyHz,predictedFrequencyHz;boolean available,cancellable,suppressed,controllerEnabled;int successUpdates;long lastRecipeMs,idleSinceMs;
        Lane(MechanicalFrequency model,double f){this.model=model;label=model.name();currentFrequencyHz=f;oscillator.frequencyHz=f;oscillator.targetFrequencyHz=f;}
    }
    private static final class DiscoveredLane {
        final String id,label;final double anchorFrequencyHz;final AutoController controller=new AutoController();final AdaptiveFrequencyTracker tracker=new AdaptiveFrequencyTracker();final Oscillator oscillator=new Oscillator();long referenceEpoch;double referencePhase;double currentFrequencyHz;long lastStrongMs,lastRecipeMs,idleSinceMs;boolean cancellable;int successUpdates;
        DiscoveredLane(String id,double f,long now){this.id=id;label=String.format(Locale.US,"Auto %.1f Hz",f);anchorFrequencyHz=f;currentFrequencyHz=f;lastStrongMs=now;oscillator.frequencyHz=f;oscillator.targetFrequencyHz=f;tracker.setBounds(Math.max(8.0,f-DISCOVERY_TRACK_HALF_WIDTH_HZ),Math.min(200.0,f+DISCOVERY_TRACK_HALF_WIDTH_HZ));}
    }
    private record DiscoveryResult(boolean moved,int cancelling) { }
    private static final class Oscillator {double phase=0,frequencyHz=0,targetFrequencyHz=0,real=0,imag=0,targetReal=0,targetImag=0;}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
