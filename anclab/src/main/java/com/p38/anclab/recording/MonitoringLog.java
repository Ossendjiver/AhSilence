package com.p38.anclab.recording;

import android.content.Context;
import com.p38.anclab.storage.AncStorage;
import com.p38.anclab.telemetry.TelemetryState;
import com.p38.anclab.telemetry.VehicleTelemetryRuntime;
import java.io.File;
import java.io.FileWriter;

public final class MonitoringLog {
    private final Context context; private final AncStorage storage; private File file; private FileWriter writer; private String stamp; private long lastMs;
    public MonitoringLog(Context c,AncStorage s){context=c.getApplicationContext();storage=s;}
    public synchronized void start(){if(writer!=null)return;try{stamp=storage.timestamp();file=new File(context.getCacheDir(),"anc-monitoring-"+stamp+".csv");writer=new FileWriter(file);writer.write("utcMs,inputRms,outputRms,mode,laneState,safetyStatus,gpsSpeedKmh,obdSpeedKmh,rpm,engineLoad,throttle,gpsStatus,obdStatus,inputRoute,outputRoute\n");writer.flush();lastMs=0;}catch(Exception ignored){writer=null;}}
    public synchronized void sample(long now,float in,float out,String mode,String laneState,String safetyStatus,String input,String output){if(writer==null||now-lastMs<1000)return;lastMs=now;try{TelemetryState t=VehicleTelemetryRuntime.get()==null?TelemetryState.empty():VehicleTelemetryRuntime.get().telemetry();writer.write(now+","+in+","+out+",\""+clean(mode)+"\",\""+clean(laneState)+"\",\""+clean(safetyStatus)+"\","+number(t.gpsSpeedKmh())+","+number(t.obdSpeedKmh())+","+number(t.engineRpm())+","+number(t.engineLoadPercent())+","+number(t.throttlePercent())+",\""+clean(t.gpsStatus())+"\",\""+clean(t.obdStatus())+"\",\""+clean(input)+"\",\""+clean(output)+"\"\n");if(now%10000<1000)writer.flush();}catch(Exception ignored){}}
    public synchronized void stop(){if(writer==null)return;try{writer.flush();writer.close();}catch(Exception ignored){}writer=null;if(storage.isConnected()&&file!=null)storage.copyFileToTree(file,"logs/monitoring-"+stamp+".csv","text/csv");if(file!=null)file.delete();}
    private String clean(String s){return s==null?"":s.replace("\"","'").replace("\n"," ").replace("\r"," ");}
    private String number(double value){return Double.isFinite(value)?Double.toString(value):"";}
}
