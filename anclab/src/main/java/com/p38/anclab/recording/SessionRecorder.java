package com.p38.anclab.recording;

import android.content.Context;
import com.p38.anclab.storage.AncStorage;
import java.io.File;
import java.io.FileWriter;

public final class SessionRecorder {
    private final Context context; private final AncStorage storage; private WavWriter wav; private File wavFile; private File csvFile; private FileWriter csv; private String stamp; private boolean active; private long frames; private int sampleRate;
    public SessionRecorder(Context c,AncStorage s){context=c.getApplicationContext();storage=s;}
    public synchronized boolean start(int sampleRate){if(active)return true;try{this.sampleRate=sampleRate;stamp=storage.timestamp();wavFile=new File(context.getCacheDir(),"anc-session-"+stamp+".wav");csvFile=new File(context.getCacheDir(),"anc-session-"+stamp+".csv");wav=new WavWriter(wavFile,sampleRate,2);csv=new FileWriter(csvFile);csv.write("seconds,inputRms,outputRms\n");frames=0;active=true;return true;}catch(Exception e){active=false;return false;}}
    public synchronized void onAudio(float[] in,float[] out,int n){if(!active)return;try{wav.writeMonoPair(in,out,n);frames+=n;if((frames/n)%20==0){double a=0,b=0;for(int i=0;i<n;i++){a+=in[i]*in[i];b+=out[i]*out[i];}csv.write(String.format(java.util.Locale.US,"%.3f,%.6f,%.6f\n",frames/(double)sampleRate,Math.sqrt(a/n),Math.sqrt(b/n)));csv.flush();}}catch(Exception ignored){}}
    public synchronized void stop(){if(!active)return;active=false;try{wav.close();}catch(Exception ignored){}try{csv.flush();csv.close();}catch(Exception ignored){}if(storage.isConnected()){storage.copyFileToTree(wavFile,"wav/session-"+stamp+".wav","audio/wav");storage.copyFileToTree(csvFile,"logs/session-"+stamp+".csv","text/csv");}if(wavFile!=null)wavFile.delete();if(csvFile!=null)csvFile.delete();}
    public synchronized boolean isActive(){return active;}
}
