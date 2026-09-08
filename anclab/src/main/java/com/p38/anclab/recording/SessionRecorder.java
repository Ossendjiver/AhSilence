package com.p38.anclab.recording;

import android.content.Context;
import com.p38.anclab.storage.AncStorage;
import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

public final class SessionRecorder {
    public record Diagnostics(double ancBandRms,double predictorConfidence,String predictableTones,String stationaryNoise,String safetyStatus){}
    private final Context context; private final AncStorage storage; private WavWriter wav; private File wavFile; private File csvFile; private FileWriter csv; private String stamp; private boolean active; private long frames,lastCsvFrame; private int sampleRate;
    public SessionRecorder(Context c,AncStorage s){context=c.getApplicationContext();storage=s;}
    public synchronized boolean start(int sampleRate){if(active)return true;try{this.sampleRate=sampleRate;stamp=storage.timestamp();wavFile=new File(context.getCacheDir(),"anc-session-"+stamp+".wav");csvFile=new File(context.getCacheDir(),"anc-session-"+stamp+".csv");wav=new WavWriter(wavFile,sampleRate,2);csv=new FileWriter(csvFile);csv.write("seconds,rawInputRms,ancBandRms,outputRms,predictorConfidence,predictableTones,stationaryNoise,safetyStatus\n");frames=lastCsvFrame=0;active=true;return true;}catch(Exception e){active=false;return false;}}
    public synchronized void onAudio(float[] in,float[] out,int n){onAudio(in,out,n,null);}
    public synchronized void onAudio(float[] in,float[] out,int n,Diagnostics d){if(!active)return;try{wav.writeMonoPair(in,out,n);frames+=n;if(frames-lastCsvFrame>=sampleRate){double a=0,b=0;for(int i=0;i<n;i++){a+=in[i]*in[i];b+=out[i]*out[i];}double raw=Math.sqrt(a/Math.max(1,n)),outRms=Math.sqrt(b/Math.max(1,n));csv.write(String.format(Locale.US,"%.3f,%.9f,%.9f,%.9f,%.5f,\"%s\",\"%s\",\"%s\"\n",frames/(double)sampleRate,raw,d==null?Double.NaN:d.ancBandRms(),outRms,d==null?Double.NaN:d.predictorConfidence(),clean(d==null?"":d.predictableTones()),clean(d==null?"":d.stationaryNoise()),clean(d==null?"":d.safetyStatus())));csv.flush();lastCsvFrame=frames;}}catch(Exception ignored){}}
    public synchronized void stop(){if(!active)return;active=false;try{wav.close();}catch(Exception ignored){}try{csv.flush();csv.close();}catch(Exception ignored){}if(storage.isConnected()){storage.copyFileToTree(wavFile,"wav/session-"+stamp+".wav","audio/wav");storage.copyFileToTree(csvFile,"logs/session-"+stamp+".csv","text/csv");}if(wavFile!=null)wavFile.delete();if(csvFile!=null)csvFile.delete();}
    public synchronized boolean isActive(){return active;}
    private static String clean(String s){return s==null?"":s.replace("\"","'").replace("\n"," ").replace("\r"," ");}
}
