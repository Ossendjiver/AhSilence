package com.p38.anclab.audio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;

import com.p38.anclab.dsp.FeedbackFxNlms;
import com.p38.anclab.dsp.HeadphoneFeedforwardFxNlms;
import com.p38.anclab.dsp.PredictableFrequencyExcluder;
import com.p38.anclab.dsp.VehicleNarrowbandBank;
import com.p38.anclab.profile.HeadphoneCalibration;
import com.p38.anclab.profile.MechanicalFrequency;
import com.p38.anclab.profile.ProfileStore;
import com.p38.anclab.profile.VehicleCancellationRecipe;
import com.p38.anclab.recording.AppLog;
import com.p38.anclab.recording.MonitoringLog;
import com.p38.anclab.recording.SessionRecorder;
import com.p38.anclab.recording.WavWriter;
import com.p38.anclab.storage.AncStorage;
import com.p38.anclab.telemetry.VehicleTelemetryRuntime;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 48 kHz full-duplex ANC runtime. ANC Lab deliberately never requests Android audio focus. */
public final class AudioEngine {
    public static final int SAMPLE_RATE=48000;
    private static final String TAG="AudioEngine";
    private static final int GRAPH_POINTS=720;
    private static final int GRAPH_DECIMATION=24;

    private enum Mode { NONE, HEADPHONES, VEHICLE }
    private final Context context;
    private final AudioManager audioManager;
    private final AncStorage storage;
    private final ProfileStore profiles;
    private final SessionRecorder recorder;
    private final MonitoringLog monitoringLog;
    private final VehicleTelemetryRuntime vehicleTelemetry;
    private final AtomicBoolean running=new AtomicBoolean(false);
    private AudioRecord record;
    private AudioTrack track;
    private Thread worker;
    private volatile Mode mode=Mode.NONE;
    private volatile String activeProfile=ProfileStore.PROFILE_HEADPHONES;
    private int inputDeviceId=0,outputDeviceId=0;
    private String inputRoute="System default",outputRoute="System default";
    private HeadphoneCalibration calibration;
    private HeadphoneFeedforwardFxNlms headphoneFx;
    private FeedbackFxNlms vehicleFx;
    private VehicleNarrowbandBank vehicleNarrowband;
    private PredictableFrequencyExcluder vehicleExcluder;
    private volatile boolean vehicleBroadbandEnabled=false;
    private volatile int antiNoisePercent=50;
    private volatile float inputRms=0f,outputRms=0f;
    private volatile String lastError="None",safetyStatus="";
    private volatile boolean monitorLogEnabled=true;
    private final ExecutorService recipeWriter=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ANC-Recipe-Writer");t.setDaemon(true);return t;});
    private long lastVehicleRecipeDrainMs=0L;

    private int expectedOutputRouteId=0;
    private int routeMissingBlocks=0;
    private int volumeReferenceIndex=-1,volumeReferenceMax=-1,lastVolumeIndex=-1;
    private float volumeReferenceDb=Float.NaN,lastVolumeDb=Float.NaN;
    private volatile float routeGainCompensation=1f;
    private int observedSafetyTrips=0,safetyTripsInWindow=0;
    private long safetyWindowStartMs=0L;
    private long lastVehicleFrequencyRevision=-1L,lastVehicleExcluderUpdateMs=0L;

    private final Object graphLock=new Object();
    private final float[] graphReference=new float[GRAPH_POINTS],graphDrive=new float[GRAPH_POINTS],graphPredictedCancellation=new float[GRAPH_POINTS],graphPredictedResidual=new float[GRAPH_POINTS];
    private int graphWrite=0,graphCount=0,graphDecimator=0;

    public static final class DeviceChoice {
        public final int id; public final String name; public final AudioDeviceInfo info;
        DeviceChoice(int id,String name,AudioDeviceInfo info){this.id=id;this.name=name;this.info=info;}
        @Override public String toString(){return name;}
    }
    public static final class CalibrationResult {
        public final boolean success; public final HeadphoneCalibration calibration; public final String message;
        public CalibrationResult(boolean s,HeadphoneCalibration c,String m){success=s;calibration=c;message=m;}
    }
    public static final class GraphSnapshot {
        public final float[] reference,drive,predictedCancellation,predictedResidual;
        public final float sampleRateHz; public final boolean running;
        GraphSnapshot(float[] r,float[] d,float[] c,float[] e,float sr,boolean active){reference=r;drive=d;predictedCancellation=c;predictedResidual=e;sampleRateHz=sr;running=active;}
    }
    private static final class VolumeSnapshot {
        final int index,max,deviceType; final float db;
        VolumeSnapshot(int i,int m,int t,float d){index=i;max=m;deviceType=t;db=d;}
    }

    public AudioEngine(Context c,AncStorage storage){
        context=c.getApplicationContext();audioManager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);this.storage=storage;
        profiles=new ProfileStore(storage);
        recorder=new SessionRecorder(context,storage);monitoringLog=new MonitoringLog(context,storage);vehicleTelemetry=VehicleTelemetryRuntime.get();
    }

    public List<DeviceChoice> listInputDevices(){return listDevices(AudioManager.GET_DEVICES_INPUTS);}
    public List<DeviceChoice> listOutputDevices(){return listDevices(AudioManager.GET_DEVICES_OUTPUTS);}
    private List<DeviceChoice> listDevices(int flag){List<DeviceChoice> out=new ArrayList<>();out.add(new DeviceChoice(0,"System default",null));for(AudioDeviceInfo d:audioManager.getDevices(flag))out.add(new DeviceChoice(d.getId(),typeName(d)+" · "+d.getProductName(),d));return out;}
    public void setPreferredInput(DeviceChoice d){inputDeviceId=d==null?0:d.id;inputRoute=d==null?"System default":d.name;}
    public void setPreferredOutput(DeviceChoice d){outputDeviceId=d==null?0:d.id;outputRoute=d==null?"System default":d.name;}
    public String getInputRoute(){return inputRoute;} public String getOutputRoute(){return outputRoute;}
    public int getInputDeviceId(){return inputDeviceId;} public int getOutputDeviceId(){return outputDeviceId;}
    public float getInputRms(){return inputRms;} public float getOutputRms(){return outputRms;}
    public String getLastError(){return lastError;} public String getSafetyStatus(){return safetyStatus;}
    public boolean isRunning(){return running.get();} public String getActiveProfile(){return activeProfile;}
    public boolean isVehicleBroadbandEnabled(){return vehicleBroadbandEnabled;}
    public int getAntiNoisePercent(){return antiNoisePercent;}
    public void setMonitorLogEnabled(boolean enabled){monitorLogEnabled=enabled;}
    public void applyCalibration(HeadphoneCalibration c){calibration=c;if(c!=null)AppLog.i(TAG,"Stored route calibration applied: "+c.profileId+" "+String.format(Locale.US,"%.1f ms / %.0f%%",c.delayMs(),c.quality*100f));}
    public HeadphoneCalibration getCalibration(){return calibration;}

    public void setAntiNoisePercent(int percent){
        antiNoisePercent=Math.max(0,Math.min(100,percent));float scale=antiNoisePercent/100f;
        HeadphoneFeedforwardFxNlms h=headphoneFx;if(h!=null)h.setUserOutputScale(scale);
        FeedbackFxNlms v=vehicleFx;if(v!=null)v.setUserOutputScale(scale);
        VehicleNarrowbandBank n=vehicleNarrowband;if(n!=null)n.setUserOutputScale(scale);
    }

    /** Optional speculative vehicle broadband can be toggled live; narrowband lanes keep running. */
    public synchronized void setVehicleBroadbandEnabled(boolean enabled){
        vehicleBroadbandEnabled=enabled;
        if(mode!=Mode.VEHICLE||!running.get())return;
        if(!enabled){vehicleFx=null;safetyStatus="Speculative broadband OFF · telemetry narrowband lanes continue";}
        else if(calibration!=null){vehicleFx=new FeedbackFxNlms(calibration.secondaryPath,calibration.delaySamples,128,calibration.safeOutputCeiling);configureVehicleFx(vehicleFx);safetyStatus="Speculative broadband ON · live predictable lane bands excluded";}
    }

    public GraphSnapshot getGraphSnapshot(){synchronized(graphLock){int n=graphCount;float[] r=new float[n],d=new float[n],c=new float[n],e=new float[n];int start=(graphWrite-n+GRAPH_POINTS)%GRAPH_POINTS;for(int i=0;i<n;i++){int p=(start+i)%GRAPH_POINTS;r[i]=graphReference[p];d[i]=graphDrive[p];c[i]=graphPredictedCancellation[p];e[i]=graphPredictedResidual[p];}return new GraphSnapshot(r,d,c,e,SAMPLE_RATE/(float)GRAPH_DECIMATION,running.get());}}

    @SuppressLint("MissingPermission")
    public CalibrationResult calibrateHeadphones(){return calibrateRoute(ProfileStore.PROFILE_HEADPHONES);}

    /** Measure selected output -> selected microphone path for Headphones, P38 or E46. */
    @SuppressLint("MissingPermission")
    public CalibrationResult calibrateRoute(String profileId){
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return new CalibrationResult(false,null,"Microphone permission is required");
        stop();activeProfile=profileId==null?ProfileStore.PROFILE_HEADPHONES:profileId;
        final int lead=4096,probeLen=16384,total=48000;float[] probe=new float[total];int lfsr=0x7fff;
        float probeLevel=ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)?0.08f:0.035f;
        for(int i=0;i<probeLen;i++){int bit=((lfsr>>0)^(lfsr>>1))&1;lfsr=(lfsr>>1)|(bit<<14);probe[lead+i]=((lfsr&1)==0?-1f:1f)*probeLevel;}
        float[] captured=new float[total];
        try{
            AudioRecord r=buildRecord();AudioTrack t=buildTrack();if(r==null||t==null)throw new IllegalStateException("Could not open selected audio route");
            r.startRecording();t.play();t.write(new float[192],0,192,AudioTrack.WRITE_BLOCKING);
            AudioDeviceInfo routed=t.getRoutedDevice();
            if(ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)&&routed!=null&&!isHeadphoneLike(routed.getType()))throw new IllegalStateException("Headphone calibration output is not routed to headphones");
            VolumeSnapshot volumeStart=volumeSnapshot(t);
            int block=256;float[] ob=new float[block],ib=new float[block];int pos=0;
            while(pos<total){int n=Math.min(block,total-pos);System.arraycopy(probe,pos,ob,0,n);t.write(ob,0,n,AudioTrack.WRITE_BLOCKING);int q=r.read(ib,0,n,AudioRecord.READ_BLOCKING);if(q>0)System.arraycopy(ib,0,captured,pos,Math.min(q,n));pos+=n;}
            VolumeSnapshot volumeEnd=volumeSnapshot(t);
            t.stop();r.stop();t.release();r.release();
            if(volumeStart.index!=volumeEnd.index){saveCalibrationWavs(probe,captured);return new CalibrationResult(false,null,"Media volume changed during calibration; repeat at a fixed volume");}

            int maxLag=Math.min(24000,total-lead-4096-1),bestLag=0;double best=0,bestSigned=0,pe=0;for(int i=0;i<4096;i++){double xx=probe[lead+i];pe+=xx*xx;}
            for(int lag=0;lag<maxLag;lag+=2){double dot=0,re=1e-12;int base=lead+lag;for(int i=0;i<4096;i++){double xx=probe[lead+i],y=captured[base+i];dot+=xx*y;re+=y*y;}double corr=dot/Math.sqrt(pe*re);if(Math.abs(corr)>best){best=Math.abs(corr);bestSigned=corr;bestLag=lag;}}
            int start=Math.max(0,bestLag-4),end=Math.min(maxLag,bestLag+5);for(int lag=start;lag<=end;lag++){double dot=0,re=1e-12;int base=lead+lag;for(int i=0;i<4096;i++){double xx=probe[lead+i],y=captured[base+i];dot+=xx*y;re+=y*y;}double corr=dot/Math.sqrt(pe*re);if(Math.abs(corr)>best){best=Math.abs(corr);bestSigned=corr;bestLag=lag;}}
            if(best<0.025){saveCalibrationWavs(probe,captured);return new CalibrationResult(false,null,"Calibration probe was not detected clearly enough (quality "+String.format(Locale.US,"%.0f%%",best*100)+")");}
            int firLen=128;float[] h=new float[firLen],xh=new float[firLen];int xp=0;float mu=0.35f;int nFit=Math.min(probeLen-256,total-lead-bestLag-256);
            for(int nn=0;nn<nFit;nn++){float xx=probe[lead+nn];xh[xp]=xx;float yh=0,norm=1e-7f;int p=xp;for(int k=0;k<firLen;k++){yh+=h[k]*xh[p];norm+=xh[p]*xh[p];if(--p<0)p=firLen-1;}float target=captured[lead+bestLag+nn],err=target-yh,step=mu*err/norm;p=xp;for(int k=0;k<firLen;k++){h[k]+=step*xh[p];if(--p<0)p=firLen-1;}if(++xp==firLen)xp=0;}
            HeadphoneCalibration c=new HeadphoneCalibration();c.profileId=activeProfile;c.sampleRateHz=SAMPLE_RATE;c.inputDeviceId=inputDeviceId;c.outputDeviceId=outputDeviceId;c.inputRoute=inputRoute;c.outputRoute=outputRoute;c.delaySamples=bestLag;c.quality=(float)best;c.utcMs=System.currentTimeMillis();c.secondaryPath=h;c.safeOutputCeiling=ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)?(bestLag>2400?0.12f:0.18f):0.08f;c.minimumCancellationHz=ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)?15f:20f;c.maximumCancellationHz=ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)?600f:200f;c.frequencyBandVerified=false;c.mediaVolumeIndex=volumeStart.index;c.mediaVolumeMax=volumeStart.max;c.mediaVolumeDb=volumeStart.db;calibration=c;saveCalibrationWavs(probe,captured);
            AppLog.i(TAG,"Route calibration complete profile="+activeProfile+" delay="+bestLag+" samples quality="+best+" signedCorr="+bestSigned+" mediaDb="+volumeStart.db);
            return new CalibrationResult(true,c,"Calibration complete");
        }catch(Exception e){lastError=e.getMessage();AppLog.e(TAG,"Calibration failed",e);return new CalibrationResult(false,null,"Calibration failed: "+e.getMessage());}
    }

    private void saveCalibrationWavs(float[] probe,float[] response){try{String s=storage.timestamp();File a=new File(context.getCacheDir(),"probe-"+s+".wav"),b=new File(context.getCacheDir(),"response-"+s+".wav");try(WavWriter w=new WavWriter(a,SAMPLE_RATE,1)){w.writeMonoPair(probe,probe,probe.length);}try(WavWriter w=new WavWriter(b,SAMPLE_RATE,1)){w.writeMonoPair(response,response,response.length);}if(storage.isConnected()){storage.copyFileToTree(a,"wav/calibration-probe-"+s+".wav","audio/wav");storage.copyFileToTree(b,"wav/calibration-response-"+s+".wav","audio/wav");}a.delete();b.delete();}catch(Exception ignored){}}

    @SuppressLint("MissingPermission")
    public synchronized boolean startHeadphoneAnc(){if(calibration==null){lastError="No stored headphone calibration";return false;}return startInternal(Mode.HEADPHONES,ProfileStore.PROFILE_HEADPHONES,false,List.of());}

    @SuppressLint("MissingPermission")
    public synchronized boolean startVehicleAnc(String profileId,boolean broadbandEnabled,List<MechanicalFrequency> mechanicalModels){if(calibration==null){lastError="No stored vehicle route calibration";return false;}return startInternal(Mode.VEHICLE,profileId,broadbandEnabled,mechanicalModels==null?List.of():mechanicalModels);}

    private boolean startInternal(Mode requested,String profileId,boolean broadbandEnabled,List<MechanicalFrequency> mechanicalModels){
        if(running.get())return true;
        try{
            mode=requested;activeProfile=profileId;vehicleBroadbandEnabled=broadbandEnabled;safetyStatus="";lastError="None";
            record=buildRecord();track=buildTrack();if(record==null||track==null)throw new IllegalStateException("Could not open selected audio route");
            clearGraph();record.startRecording();track.play();track.write(new float[192],0,192,AudioTrack.WRITE_BLOCKING);
            AudioDeviceInfo routed=track.getRoutedDevice();
            if(requested==Mode.HEADPHONES){
                if(routed==null||!isHeadphoneLike(routed.getType()))throw new IllegalStateException("ANC stopped: output is not routed to headphones");
                headphoneFx=new HeadphoneFeedforwardFxNlms(calibration.secondaryPath,calibration.delaySamples,calibration.safeOutputCeiling);headphoneFx.setAdaptationRate(calibration.delaySamples>2400?0.012f:0.035f);headphoneFx.setUserOutputScale(antiNoisePercent/100f);
            }else{
                if(vehicleTelemetry==null)throw new IllegalStateException("Vehicle telemetry runtime is unavailable");
                vehicleTelemetry.activateProfile(profileId,mechanicalModels);
                vehicleNarrowband=new VehicleNarrowbandBank(mechanicalModels,vehicleTelemetry,
                        calibration.safeOutputCeiling,antiNoisePercent/100f,
                        calibration.minimumCancellationHz,calibration.maximumCancellationHz,
                        vehicleRecipeRouteKey(),profiles.loadCancellationRecipes(profileId));
                vehicleExcluder=new PredictableFrequencyExcluder(SAMPLE_RATE,vehicleNarrowband.frequenciesHz());
                lastVehicleFrequencyRevision=vehicleNarrowband.frequencyRevision();lastVehicleExcluderUpdateMs=System.currentTimeMillis();
                if(broadbandEnabled){vehicleFx=new FeedbackFxNlms(calibration.secondaryPath,calibration.delaySamples,128,calibration.safeOutputCeiling);configureVehicleFx(vehicleFx);}else vehicleFx=null;
            }
            expectedOutputRouteId=routed==null?0:routed.getId();routeMissingBlocks=0;initializeVolumeCompensation();
            running.set(true);if(monitorLogEnabled)monitoringLog.start();worker=new Thread(this::runLoop,"ANC-Lab-Audio");worker.setPriority(Thread.MAX_PRIORITY);worker.start();
            AppLog.i(TAG,"ANC started profile="+activeProfile+" mode="+mode+" broad="+vehicleBroadbandEnabled+" predictableLanes="+(vehicleNarrowband==null?0:vehicleNarrowband.frequenciesHz().length)+" antiNoise="+antiNoisePercent+"% route="+outputRoute);
            return true;
        }catch(Exception e){lastError=e.getMessage();AppLog.e(TAG,"Start failed",e);stop();return false;}
    }

    private void configureVehicleFx(FeedbackFxNlms fx){fx.setAdaptationRate(0.018f);fx.setUserOutputScale(antiNoisePercent/100f);fx.setRouteGainCompensation(1f);}
    private void clearGraph(){synchronized(graphLock){graphWrite=graphCount=graphDecimator=0;}}
    private void graphSample(float reference,float drive,float cancellation,float residual){if(++graphDecimator<GRAPH_DECIMATION)return;graphDecimator=0;synchronized(graphLock){graphReference[graphWrite]=reference;graphDrive[graphWrite]=drive;graphPredictedCancellation[graphWrite]=cancellation;graphPredictedResidual[graphWrite]=residual;graphWrite=(graphWrite+1)%GRAPH_POINTS;if(graphCount<GRAPH_POINTS)graphCount++;}}

    private void runLoop(){
        int block=192;float[] in=new float[block],out=new float[block];
        while(running.get()){
            int n=record.read(in,0,block,AudioRecord.READ_BLOCKING);if(n==AudioRecord.ERROR_DEAD_OBJECT){lastError="Microphone disconnected · ANC stopped";break;}if(n<=0)continue;
            if(!routeStillSafe())break;updateVolumeCompensationIfNeeded();double inputEnergy=0,outputEnergy=0;
            for(int i=0;i<n;i++){
                float y=0f,ref=0f,cancel=0f,residual=0f;
                inputEnergy+=in[i]*in[i];
                if(mode==Mode.HEADPHONES&&headphoneFx!=null){
                    y=headphoneFx.process(in[i]);ref=headphoneFx.diagnosticReference();cancel=headphoneFx.diagnosticPredictedCancellation();residual=headphoneFx.diagnosticPredictedResidual();
                }else if(mode==Mode.VEHICLE){
                    float narrowModel=vehicleNarrowband==null?0f:vehicleNarrowband.process(in[i]);
                    float broadModel=0f;
                    if(vehicleBroadbandEnabled&&vehicleFx!=null){float broadIn=vehicleExcluder==null?in[i]:vehicleExcluder.process(in[i]);vehicleFx.process(broadIn);broadModel=vehicleFx.diagnosticModelDrive();ref=vehicleFx.diagnosticReference();cancel=vehicleFx.diagnosticPredictedCancellation();residual=vehicleFx.diagnosticMeasuredResidual();}
                    else {ref=in[i];cancel=narrowModel;residual=in[i];}
                    float totalModelCeiling=calibration.safeOutputCeiling*(antiNoisePercent/100f);
                    float combinedModel=clamp(narrowModel+broadModel,-totalModelCeiling,totalModelCeiling);
                    y=clamp(combinedModel*routeGainCompensation,-0.5f,0.5f);
                }
                out[i]=y;outputEnergy+=y*y;graphSample(ref,y,cancel,residual);
            }
            int w=track.write(out,0,n,AudioTrack.WRITE_BLOCKING);if(w==AudioTrack.ERROR_DEAD_OBJECT){lastError="Audio output disconnected · ANC stopped";break;}if(w<0){lastError="AudioTrack write error "+w;break;}
            if(mode==Mode.HEADPHONES&&headphoneFx!=null){inputRms=headphoneFx.inputRms();outputRms=headphoneFx.outputRms();checkSafetyTrips(headphoneFx.safetyTrips(),headphoneFx.safetyStatus());}
            else if(mode==Mode.VEHICLE){
                inputRms=(float)Math.sqrt(inputEnergy/Math.max(1,n));outputRms=(float)Math.sqrt(outputEnergy/Math.max(1,n));
                if(vehicleFx!=null)checkSafetyTrips(vehicleFx.safetyTrips(),vehicleFx.safetyStatus());
                updateVehicleBroadbandExclusions();
                persistVehicleRecipesIfDue();
                String nb=vehicleNarrowband==null?"":vehicleNarrowband.status();if((safetyStatus==null||safetyStatus.isEmpty()||safetyStatus.startsWith("Predictable narrowband"))&&!nb.isEmpty())safetyStatus=nb+(vehicleBroadbandEnabled?" · broadband ON":" · broadband OFF");
            }
            recorder.onAudio(in,out,n);
            String algorithm=mode==Mode.HEADPHONES?"HEADPHONE_PREDICTIVE_FXNLMS_15_600":vehicleBroadbandEnabled?"VEHICLE_TELEMETRY_NARROWBAND_PLUS_MEASURED_ERROR_BROADBAND":"VEHICLE_TELEMETRY_NARROWBAND";
            monitoringLog.sample(System.currentTimeMillis(),inputRms,outputRms,algorithm,inputRoute,outputRoute);
        }
        running.set(false);try{if(track!=null){track.pause();track.flush();}}catch(Exception ignored){}
    }

    private void updateVehicleBroadbandExclusions(){
        if(vehicleNarrowband==null||vehicleExcluder==null)return;long rev=vehicleNarrowband.frequencyRevision();long now=System.currentTimeMillis();
        if(rev==lastVehicleFrequencyRevision||now-lastVehicleExcluderUpdateMs<750)return;
        vehicleExcluder.setFrequencies(vehicleNarrowband.frequenciesHz());lastVehicleFrequencyRevision=rev;lastVehicleExcluderUpdateMs=now;
    }

    private void persistVehicleRecipesIfDue(){
        VehicleNarrowbandBank bank=vehicleNarrowband;if(bank==null)return;long now=System.currentTimeMillis();
        if(now-lastVehicleRecipeDrainMs<1000)return;lastVehicleRecipeDrainMs=now;
        List<VehicleCancellationRecipe> learned=bank.drainLearnedRecipes();if(learned.isEmpty())return;
        String profile=activeProfile;recipeWriter.execute(()->{for(VehicleCancellationRecipe recipe:learned)
            profiles.appendCancellationRecipe(profile,recipe);});
    }

    private String vehicleRecipeRouteKey(){
        long revision=calibration==null?0L:calibration.utcMs;
        return activeProfile+"|in="+inputDeviceId+"|out="+outputDeviceId+"|cal="+revision;
    }

    private void checkSafetyTrips(int trips,String status){if(trips<=observedSafetyTrips)return;observedSafetyTrips=trips;safetyStatus=status;lastError=status;long now=System.currentTimeMillis();if(now-safetyWindowStartMs>15000){safetyWindowStartMs=now;safetyTripsInWindow=0;}safetyTripsInWindow++;if(safetyTripsInWindow>=3){lastError="ANC safety stop · repeated feedback/runaway detected";safetyStatus=lastError;running.set(false);}}

    private boolean routeStillSafe(){
        AudioDeviceInfo routed=track==null?null:track.getRoutedDevice();
        if(routed==null){if(++routeMissingBlocks>5){lastError="Audio output route disappeared · ANC stopped";running.set(false);return false;}return true;}
        routeMissingBlocks=0;
        if(outputDeviceId!=0&&routed.getId()!=outputDeviceId){lastError="Selected audio output disconnected or rerouted · ANC stopped";running.set(false);return false;}
        if(mode==Mode.HEADPHONES&&(!isHeadphoneLike(routed.getType())||(expectedOutputRouteId!=0&&routed.getId()!=expectedOutputRouteId))){lastError="Headphones disconnected or rerouted · ANC stopped";running.set(false);return false;}
        return true;
    }

    private void initializeVolumeCompensation(){VolumeSnapshot s=volumeSnapshot(track);lastVolumeIndex=s.index;lastVolumeDb=s.db;volumeReferenceIndex=calibration!=null&&calibration.mediaVolumeIndex>=0?calibration.mediaVolumeIndex:s.index;volumeReferenceMax=calibration!=null&&calibration.mediaVolumeMax>0?calibration.mediaVolumeMax:s.max;volumeReferenceDb=calibration!=null&&Float.isFinite(calibration.mediaVolumeDb)?calibration.mediaVolumeDb:s.db;applyRouteCompensation(computeCompensation(s),false);}
    private void updateVolumeCompensationIfNeeded(){VolumeSnapshot s=volumeSnapshot(track);if(s.index==lastVolumeIndex&&sameDb(s.db,lastVolumeDb))return;lastVolumeIndex=s.index;lastVolumeDb=s.db;applyRouteCompensation(computeCompensation(s),true);}
    private float computeCompensation(VolumeSnapshot s){if(s.index<=0)return 0f;if(Float.isFinite(volumeReferenceDb)&&Float.isFinite(s.db))return AudioOutputGainCompensator.compensationFromDb(volumeReferenceDb,s.db);return AudioOutputGainCompensator.compensationFromIndex(volumeReferenceIndex,s.index,Math.max(volumeReferenceMax,s.max));}
    private void applyRouteCompensation(float gain,boolean changed){routeGainCompensation=gain;HeadphoneFeedforwardFxNlms h=headphoneFx;if(h!=null){h.setRouteGainCompensation(gain);if(changed)h.notifyRouteGainChanged();}FeedbackFxNlms v=vehicleFx;if(v!=null&&changed)v.notifyRouteGainChanged();if(changed){safetyStatus=String.format(Locale.US,"Media volume changed · ANC transport gain %.3f×",gain);AppLog.i(TAG,safetyStatus);}}

    private VolumeSnapshot volumeSnapshot(AudioTrack t){int idx=audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),max=audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);int type=AudioDeviceInfo.TYPE_UNKNOWN;AudioDeviceInfo routed=t==null?null:t.getRoutedDevice();if(routed!=null)type=routed.getType();else{AudioDeviceInfo d=findOutputDevice(outputDeviceId);if(d!=null)type=d.getType();}float db=Float.NaN;if(Build.VERSION.SDK_INT>=28&&type!=AudioDeviceInfo.TYPE_UNKNOWN){try{db=audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC,idx,type);}catch(Exception ignored){}}return new VolumeSnapshot(idx,max,type,db);}
    private AudioDeviceInfo findOutputDevice(int id){if(id==0)return null;for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(d.getId()==id)return d;return null;}
    private static boolean sameDb(float a,float b){return(!Float.isFinite(a)&&!Float.isFinite(b))||(Float.isFinite(a)&&Float.isFinite(b)&&Math.abs(a-b)<0.01f);}

    public synchronized void stop(){running.set(false);if(worker!=null&&worker!=Thread.currentThread()){try{worker.join(700);}catch(Exception ignored){}}worker=null;persistVehicleRecipesNow();try{if(record!=null){record.stop();record.release();}}catch(Exception ignored){}try{if(track!=null){track.pause();track.flush();track.stop();track.release();}}catch(Exception ignored){}record=null;track=null;headphoneFx=null;vehicleFx=null;vehicleNarrowband=null;vehicleExcluder=null;mode=Mode.NONE;monitoringLog.stop();if(recorder.isActive())recorder.stop();inputRms=outputRms=0f;expectedOutputRouteId=0;observedSafetyTrips=safetyTripsInWindow=0;routeGainCompensation=1f;lastVehicleFrequencyRevision=-1;}

    private void persistVehicleRecipesNow(){
        VehicleNarrowbandBank bank=vehicleNarrowband;if(bank==null)return;
        List<VehicleCancellationRecipe> learned=bank.drainLearnedRecipes();if(learned.isEmpty())return;
        String profile=activeProfile;recipeWriter.execute(()->{for(VehicleCancellationRecipe recipe:learned)
            profiles.appendCancellationRecipe(profile,recipe);});
    }
    public boolean startRecording(){return recorder.start(SAMPLE_RATE);}public void stopRecording(){recorder.stop();}public boolean isRecording(){return recorder.isActive();}

    @SuppressLint("MissingPermission")
    private AudioRecord buildRecord(){int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_FLOAT);AudioFormat f=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();AudioRecord r;try{r=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED).setAudioFormat(f).setBufferSizeInBytes(Math.max(min,192*4*4)).build();}catch(Exception e){r=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(f).setBufferSizeInBytes(Math.max(min,192*4*4)).build();}if(inputDeviceId!=0){boolean found=false;for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))if(d.getId()==inputDeviceId){if(!r.setPreferredDevice(d))throw new IllegalStateException("Android rejected the selected microphone");found=true;break;}if(!found)throw new IllegalStateException("The selected microphone is no longer connected");}return r;}
    private AudioTrack buildTrack(){int min=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT);AudioTrack.Builder b=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min,192*4*4));if(Build.VERSION.SDK_INT>=26)b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);AudioTrack t=b.build();if(outputDeviceId!=0){boolean found=false;for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(d.getId()==outputDeviceId){if(!t.setPreferredDevice(d))throw new IllegalStateException("Android rejected the selected output");found=true;break;}if(!found)throw new IllegalStateException("The selected output is no longer connected");}return t;}

    private boolean isHeadphoneLike(int type){return type==AudioDeviceInfo.TYPE_WIRED_HEADPHONES||type==AudioDeviceInfo.TYPE_WIRED_HEADSET||type==AudioDeviceInfo.TYPE_USB_HEADSET||type==AudioDeviceInfo.TYPE_USB_DEVICE||type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP||(Build.VERSION.SDK_INT>=31&&type==AudioDeviceInfo.TYPE_BLE_HEADSET);}
    private String typeName(AudioDeviceInfo d){switch(d.getType()){case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:return"Bluetooth A2DP";case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:return"Bluetooth SCO";case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:return"Wired headphones";case AudioDeviceInfo.TYPE_WIRED_HEADSET:return"Wired headset";case AudioDeviceInfo.TYPE_USB_HEADSET:return"USB headset";case AudioDeviceInfo.TYPE_USB_DEVICE:return"USB audio";case AudioDeviceInfo.TYPE_BUILTIN_MIC:return"Phone microphone";case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:return"Phone speaker";default:return"Audio device";}}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
