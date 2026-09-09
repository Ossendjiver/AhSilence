package com.p38.anclab.recording;

import android.content.Context;
import com.p38.anclab.storage.AncStorage;
import com.p38.anclab.telemetry.TelemetryState;
import com.p38.anclab.telemetry.VehicleTelemetryRuntime;
import java.io.File;
import java.io.FileWriter;

public final class SessionRecorder {
    public record Diagnostics(String laneState,String safetyStatus) { }
    private final Context context; private final AncStorage storage; private WavWriter wav; private File wavFile; private File csvFile; private FileWriter csv; private String stamp; private boolean active; private long frames,lastCsvFrame; private int sampleRate; private double inputEnergy,outputEnergy;
    public SessionRecorder(Context c,AncStorage s){context=c.getApplicationContext();storage=s;}
    public synchronized boolean start(int sampleRate){if(active)return true;try{this.sampleRate=sampleRate;stamp=storage.timestamp();wavFile=new File(context.getCacheDir(),"anc-session-"+stamp+".wav");csvFile=new File(context.getCacheDir(),"anc-session-"+stamp+".csv");wav=new WavWriter(wavFile,sampleRate,2);csv=new FileWriter(csvFile);csv.write("seconds,inputRms,outputRms,laneState,safetyStatus,gpsSpeedKmh,obdSpeedKmh,rpm,engineLoad,throttle\n");frames=lastCsvFrame=0;inputEnergy=outputEnergy=0;active=true;return true;}catch(Exception e){active=false;return false;}}
    public synchronized void onAudio(float[] in,float[] out,int n){onAudio(in,out,n,null);}
    public synchronized void onAudio(float[] in,float[] out,int n,Diagnostics diagnostics){if(!active)return;try{wav.writeMonoPair(in,out,n);for(int i=0;i<n;i++){inputEnergy+=in[i]*in[i];outputEnergy+=out[i]*out[i];}frames+=n;if(frames-lastCsvFrame>=sampleRate){long measuredFrames=frames-lastCsvFrame;TelemetryState t=VehicleTelemetryRuntime.get()==null?TelemetryState.empty():VehicleTelemetryRuntime.get().telemetry();csv.write(String.format(java.util.Locale.US,"%.3f,%.9f,%.9f,\"%s\",\"%s\",%s,%s,%s,%s,%s\n",frames/(double)sampleRate,Math.sqrt(inputEnergy/Math.max(1,measuredFrames)),Math.sqrt(outputEnergy/Math.max(1,measuredFrames)),clean(diagnostics==null?"":diagnostics.laneState()),clean(diagnostics==null?"":diagnostics.safetyStatus()),number(t.gpsSpeedKmh()),number(t.obdSpeedKmh()),number(t.engineRpm()),number(t.engineLoadPercent()),number(t.throttlePercent())));csv.flush();lastCsvFrame=frames;inputEnergy=outputEnergy=0;}}catch(Exception ignored){}}
    public synchronized void stop(){if(!active)return;active=false;try{wav.close();}catch(Exception ignored){}try{csv.flush();csv.close();}catch(Exception ignored){}if(storage.isConnected()){storage.copyFileToTree(wavFile,"wav/session-"+stamp+".wav","audio/wav");storage.copyFileToTree(csvFile,"logs/session-"+stamp+".csv","text/csv");}if(wavFile!=null)wavFile.delete();if(csvFile!=null)csvFile.delete();}
    public synchronized boolean isActive(){return active;}
    private static String clean(String value){return value==null?"":value.replace("\"","'").replace("\n"," ").replace("\r"," ");}
    private static String number(double value){return Double.isFinite(value)?Double.toString(value):"";}
}
