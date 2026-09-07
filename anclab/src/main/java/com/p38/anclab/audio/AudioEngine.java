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

import com.p38.anclab.dsp.HeadphoneFeedforwardFxNlms;
import com.p38.anclab.profile.HeadphoneCalibration;
import com.p38.anclab.recording.AppLog;
import com.p38.anclab.recording.MonitoringLog;
import com.p38.anclab.recording.SessionRecorder;
import com.p38.anclab.recording.WavWriter;
import com.p38.anclab.storage.AncStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AudioEngine {
    public static final int SAMPLE_RATE = 48000;
    private static final String TAG = "AudioEngine";
    private static final int GRAPH_POINTS = 720;
    private static final int GRAPH_DECIMATION = 24; // ~2 kHz graph-domain samples

    private final Context context;
    private final AudioManager audioManager;
    private final AncStorage storage;
    private final SessionRecorder recorder;
    private final MonitoringLog monitoringLog;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioRecord record;
    private AudioTrack track;
    private Thread worker;
    private int inputDeviceId = 0, outputDeviceId = 0;
    private String inputRoute = "System default", outputRoute = "System default";
    private HeadphoneCalibration calibration;
    private HeadphoneFeedforwardFxNlms fx;
    private volatile float inputRms = 0f, outputRms = 0f;
    private volatile String lastError = "None";
    private volatile boolean monitorLogEnabled = true;

    private final Object graphLock = new Object();
    private final float[] graphReference = new float[GRAPH_POINTS];
    private final float[] graphDrive = new float[GRAPH_POINTS];
    private final float[] graphPredictedCancellation = new float[GRAPH_POINTS];
    private final float[] graphPredictedResidual = new float[GRAPH_POINTS];
    private int graphWrite = 0;
    private int graphCount = 0;
    private int graphDecimator = 0;

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
        public final float[] reference;
        public final float[] drive;
        public final float[] predictedCancellation;
        public final float[] predictedResidual;
        public final float sampleRateHz;
        public final boolean running;
        GraphSnapshot(float[] r,float[] d,float[] c,float[] e,float sr,boolean running){
            reference=r;drive=d;predictedCancellation=c;predictedResidual=e;sampleRateHz=sr;this.running=running;
        }
    }

    public AudioEngine(Context c,AncStorage storage){context=c.getApplicationContext();audioManager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);this.storage=storage;recorder=new SessionRecorder(context,storage);monitoringLog=new MonitoringLog(context,storage);}

    public List<DeviceChoice> listInputDevices(){return listDevices(AudioManager.GET_DEVICES_INPUTS);}
    public List<DeviceChoice> listOutputDevices(){return listDevices(AudioManager.GET_DEVICES_OUTPUTS);}
    private List<DeviceChoice> listDevices(int flag){List<DeviceChoice> out=new ArrayList<>();out.add(new DeviceChoice(0,"System default",null));for(AudioDeviceInfo d:audioManager.getDevices(flag))out.add(new DeviceChoice(d.getId(),typeName(d)+" · "+d.getProductName(),d));return out;}
    public void setPreferredInput(DeviceChoice d){inputDeviceId=d==null?0:d.id;inputRoute=d==null?"System default":d.name;}
    public void setPreferredOutput(DeviceChoice d){outputDeviceId=d==null?0:d.id;outputRoute=d==null?"System default":d.name;}
    public String getInputRoute(){return inputRoute;} public String getOutputRoute(){return outputRoute;}
    public int getInputDeviceId(){return inputDeviceId;} public int getOutputDeviceId(){return outputDeviceId;}
    public float getInputRms(){return inputRms;} public float getOutputRms(){return outputRms;} public String getLastError(){return lastError;}
    public boolean isRunning(){return running.get();} public void setMonitorLogEnabled(boolean enabled){monitorLogEnabled=enabled;}
    public void applyCalibration(HeadphoneCalibration c){calibration=c;if(c!=null)AppLog.i(TAG,"Stored headphone calibration applied: "+String.format(Locale.US,"%.1f ms / %.0f%%",c.delayMs(),c.quality*100f));}
    public HeadphoneCalibration getCalibration(){return calibration;}

    public GraphSnapshot getGraphSnapshot(){
        synchronized(graphLock){
            int n=graphCount;
            float[] r=new float[n],d=new float[n],c=new float[n],e=new float[n];
            int start=(graphWrite-n+GRAPH_POINTS)%GRAPH_POINTS;
            for(int i=0;i<n;i++){
                int p=(start+i)%GRAPH_POINTS;
                r[i]=graphReference[p];d[i]=graphDrive[p];c[i]=graphPredictedCancellation[p];e[i]=graphPredictedResidual[p];
            }
            return new GraphSnapshot(r,d,c,e,SAMPLE_RATE/(float)GRAPH_DECIMATION,running.get());
        }
    }

    @SuppressLint("MissingPermission")
    public CalibrationResult calibrateHeadphones(){
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return new CalibrationResult(false,null,"Microphone permission is required");
        stop();
        final int lead=4096,probeLen=16384,total=48000;
        float[] probe=new float[total];int lfsr=0x7fff;
        for(int i=0;i<probeLen;i++){int bit=((lfsr>>0)^(lfsr>>1))&1;lfsr=(lfsr>>1)|(bit<<14);probe[lead+i]=((lfsr&1)==0?-1f:1f)*0.08f;}
        float[] captured=new float[total];
        try{
            AudioRecord r=buildRecord();AudioTrack t=buildTrack();if(r==null||t==null)throw new IllegalStateException("Could not open selected audio route");
            r.startRecording();t.play();int block=256;float[] ob=new float[block],ib=new float[block];int pos=0;
            while(pos<total){int n=Math.min(block,total-pos);System.arraycopy(probe,pos,ob,0,n);t.write(ob,0,n,AudioTrack.WRITE_BLOCKING);int q=r.read(ib,0,n,AudioRecord.READ_BLOCKING);if(q>0)System.arraycopy(ib,0,captured,pos,Math.min(q,n));pos+=n;}
            t.stop();r.stop();t.release();r.release();

            int maxLag=Math.min(24000,total-lead-4096-1),bestLag=0;double best=0,bestSigned=0,pe=0;for(int i=0;i<4096;i++){double x=probe[lead+i];pe+=x*x;}
            for(int lag=0;lag<maxLag;lag+=2){double dot=0,re=1e-12;int base=lead+lag;for(int i=0;i<4096;i++){double x=probe[lead+i],y=captured[base+i];dot+=x*y;re+=y*y;}double corr=dot/Math.sqrt(pe*re);if(Math.abs(corr)>best){best=Math.abs(corr);bestSigned=corr;bestLag=lag;}}
            int start=Math.max(0,bestLag-4),end=Math.min(maxLag,bestLag+5);for(int lag=start;lag<=end;lag++){double dot=0,re=1e-12;int base=lead+lag;for(int i=0;i<4096;i++){double x=probe[lead+i],y=captured[base+i];dot+=x*y;re+=y*y;}double corr=dot/Math.sqrt(pe*re);if(Math.abs(corr)>best){best=Math.abs(corr);bestSigned=corr;bestLag=lag;}}
            if(best<0.025){saveCalibrationWavs(probe,captured);return new CalibrationResult(false,null,"Calibration probe was not detected clearly enough (quality "+String.format(Locale.US,"%.0f%%",best*100)+")");}

            int firLen=128;float[] h=new float[firLen],xh=new float[firLen];int xp=0;float mu=0.35f;int nFit=Math.min(probeLen-256,total-lead-bestLag-256);
            for(int n=0;n<nFit;n++){float x=probe[lead+n];xh[xp]=x;float yh=0,norm=1e-7f;int p=xp;for(int k=0;k<firLen;k++){yh+=h[k]*xh[p];norm+=xh[p]*xh[p];if(--p<0)p=firLen-1;}float target=captured[lead+bestLag+n];float e=target-yh;float step=mu*e/norm;p=xp;for(int k=0;k<firLen;k++){h[k]+=step*xh[p];if(--p<0)p=firLen-1;}if(++xp==firLen)xp=0;}
            HeadphoneCalibration c=new HeadphoneCalibration();c.sampleRateHz=SAMPLE_RATE;c.inputDeviceId=inputDeviceId;c.outputDeviceId=outputDeviceId;c.inputRoute=inputRoute;c.outputRoute=outputRoute;c.delaySamples=bestLag;c.quality=(float)best;c.utcMs=System.currentTimeMillis();c.secondaryPath=h;c.safeOutputCeiling=bestLag>2400?0.12f:0.22f;calibration=c;saveCalibrationWavs(probe,captured);
            AppLog.i(TAG,"Headphone calibration complete delay="+bestLag+" samples quality="+best+" signedCorr="+bestSigned+" firTaps="+firLen);
            return new CalibrationResult(true,c,"Calibration complete");
        }catch(Exception e){lastError=e.getMessage();AppLog.e(TAG,"Calibration failed",e);return new CalibrationResult(false,null,"Calibration failed: "+e.getMessage());}
    }

    private void saveCalibrationWavs(float[] probe,float[] response){try{String s=storage.timestamp();File a=new File(context.getCacheDir(),"probe-"+s+".wav"),b=new File(context.getCacheDir(),"response-"+s+".wav");try(WavWriter w=new WavWriter(a,SAMPLE_RATE,1)){w.writeMonoPair(probe,probe,probe.length);}try(WavWriter w=new WavWriter(b,SAMPLE_RATE,1)){w.writeMonoPair(response,response,response.length);}if(storage.isConnected()){storage.copyFileToTree(a,"wav/calibration-probe-"+s+".wav","audio/wav");storage.copyFileToTree(b,"wav/calibration-response-"+s+".wav","audio/wav");}a.delete();b.delete();}catch(Exception ignored){}}

    @SuppressLint("MissingPermission")
    public synchronized boolean startHeadphoneAnc(){
        if(running.get())return true;if(calibration==null){lastError="No stored headphone calibration";return false;}
        try{
            record=buildRecord();track=buildTrack();if(record==null||track==null)throw new IllegalStateException("Could not open selected audio route");
            fx=new HeadphoneFeedforwardFxNlms(calibration.secondaryPath,calibration.delaySamples,calibration.safeOutputCeiling);
            fx.setAdaptationRate(calibration.delaySamples>2400?0.012f:0.035f);
            clearGraph();
            record.startRecording();track.play();running.set(true);if(monitorLogEnabled)monitoringLog.start();worker=new Thread(this::runLoop,"ANC-Lab-Audio");worker.setPriority(Thread.MAX_PRIORITY);worker.start();
            AppLog.i(TAG,"Headphone reference-mic 128-tap feed-forward FxNLMS started on "+outputRoute+" delaySamples="+calibration.delaySamples+" secondaryTaps="+(calibration.secondaryPath==null?0:calibration.secondaryPath.length));
            return true;
        }catch(Exception e){lastError=e.getMessage();AppLog.e(TAG,"Start failed",e);stop();return false;}
    }

    private void clearGraph(){synchronized(graphLock){graphWrite=graphCount=graphDecimator=0;}}
    private void graphSample(){
        if(++graphDecimator<GRAPH_DECIMATION)return;
        graphDecimator=0;
        synchronized(graphLock){
            graphReference[graphWrite]=fx.diagnosticReference();
            graphDrive[graphWrite]=fx.diagnosticDrive();
            graphPredictedCancellation[graphWrite]=fx.diagnosticPredictedCancellation();
            graphPredictedResidual[graphWrite]=fx.diagnosticPredictedResidual();
            graphWrite=(graphWrite+1)%GRAPH_POINTS;
            if(graphCount<GRAPH_POINTS)graphCount++;
        }
    }

    private void runLoop(){int block=192;float[] in=new float[block],out=new float[block];while(running.get()){int n=record.read(in,0,block,AudioRecord.READ_BLOCKING);if(n<=0)continue;for(int i=0;i<n;i++){out[i]=fx.process(in[i]);graphSample();}int w=track.write(out,0,n,AudioTrack.WRITE_BLOCKING);inputRms=fx.inputRms();outputRms=fx.outputRms();recorder.onAudio(in,out,n);monitoringLog.sample(System.currentTimeMillis(),inputRms,outputRms,"HEADPHONE_REFERENCE_FXNLMS_128",inputRoute,outputRoute);if(w<0){lastError="AudioTrack write error "+w;break;}}running.set(false);}

    public synchronized void stop(){running.set(false);if(worker!=null&&worker!=Thread.currentThread()){try{worker.join(600);}catch(Exception ignored){}}worker=null;try{if(record!=null){record.stop();record.release();}}catch(Exception ignored){}try{if(track!=null){track.pause();track.flush();track.stop();track.release();}}catch(Exception ignored){}record=null;track=null;monitoringLog.stop();if(recorder.isActive())recorder.stop();inputRms=outputRms=0f;}
    public boolean startRecording(){return recorder.start(SAMPLE_RATE);}public void stopRecording(){recorder.stop();}public boolean isRecording(){return recorder.isActive();}

    @SuppressLint("MissingPermission")
    private AudioRecord buildRecord(){int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_FLOAT);AudioRecord.Builder b=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(Math.max(min,192*4*4));AudioRecord r;try{r=b.build();}catch(Exception e){b=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(Math.max(min,192*4*4));r=b.build();}if(inputDeviceId!=0){for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))if(d.getId()==inputDeviceId){r.setPreferredDevice(d);break;}}return r;}
    private AudioTrack buildTrack(){int min=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT);AudioTrack.Builder b=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min,192*4*4));if(Build.VERSION.SDK_INT>=26)b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);AudioTrack t=b.build();if(outputDeviceId!=0){for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(d.getId()==outputDeviceId){t.setPreferredDevice(d);break;}}return t;}

    private String typeName(AudioDeviceInfo d){switch(d.getType()){case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:return "Bluetooth A2DP";case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:return "Bluetooth SCO";case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:return "Wired headphones";case AudioDeviceInfo.TYPE_WIRED_HEADSET:return "Wired headset";case AudioDeviceInfo.TYPE_USB_HEADSET:return "USB headset";case AudioDeviceInfo.TYPE_USB_DEVICE:return "USB audio";case AudioDeviceInfo.TYPE_BUILTIN_MIC:return "Phone microphone";case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:return "Phone speaker";default:return "Audio device";}}
}
