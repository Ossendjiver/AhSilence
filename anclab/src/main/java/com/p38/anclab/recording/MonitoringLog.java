package com.p38.anclab.recording;

import android.content.Context;
import com.p38.anclab.storage.AncStorage;
import java.io.File;
import java.io.FileWriter;

public final class MonitoringLog {
    private final Context context; private final AncStorage storage; private File file; private FileWriter writer; private String stamp; private long lastMs;
    public MonitoringLog(Context c,AncStorage s){context=c.getApplicationContext();storage=s;}
    public synchronized void start(){if(writer!=null)return;try{stamp=storage.timestamp();file=new File(context.getCacheDir(),"anc-monitoring-"+stamp+".csv");writer=new FileWriter(file);writer.write("utcMs,inputRms,outputRms,mode,inputRoute,outputRoute\n");writer.flush();}catch(Exception ignored){writer=null;}}
    public synchronized void sample(long now,float in,float out,String mode,String input,String output){if(writer==null||now-lastMs<1000)return;lastMs=now;try{writer.write(now+","+in+","+out+",\""+mode+"\",\""+clean(input)+"\",\""+clean(output)+"\"\n");if(now%10000<1000)writer.flush();}catch(Exception ignored){}}
    public synchronized void stop(){if(writer==null)return;try{writer.flush();writer.close();}catch(Exception ignored){}writer=null;if(storage.isConnected()&&file!=null)storage.copyFileToTree(file,"logs/monitoring-"+stamp+".csv","text/csv");if(file!=null)file.delete();}
    private String clean(String s){return s==null?"":s.replace("\"","'");}
}
