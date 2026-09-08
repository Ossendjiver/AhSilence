package com.p38.anclab.sensors;

import com.p38.anclab.storage.AncStorage;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persists timestamped sensor observations as an independent learning stream. Hardware adapters only
 * need to publish to SensorDataBus; legacy ANC remains fully operational if no samples ever arrive.
 */
public final class SensorLearningRecorder implements SensorDataBus.Listener {
    private final AncStorage storage;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ANC-Sensor-Learning");t.setDaemon(true);return t;});
    private volatile boolean enabled=true;
    private volatile String profileId="p38";

    public SensorLearningRecorder(AncStorage storage){this.storage=storage;SensorDataBus.get().addListener(this);}
    public void setEnabled(boolean enabled){this.enabled=enabled;}
    public void setProfileId(String profileId){if(profileId!=null&&!profileId.isBlank())this.profileId=profileId.toLowerCase(Locale.US);}

    @Override public void onSensorSample(String sensorId,SensorDataBus.Sample sample){
        if(!enabled||sample==null||!storage.isConnected())return;
        String profile=profileId;
        String line=String.format(Locale.US,"%d,%s,%.9f,%.9f,%.9f\n",sample.timestampNs(),csv(sensorId),sample.x(),sample.y(),sample.z());
        writer.execute(()->{
            String path="logs/sensors-"+profile+".csv";
            if(!storage.exists(path))storage.appendText(path,"timestamp_ns,sensor_id,x,y,z\n");
            storage.appendText(path,line);
        });
    }

    public void close(){SensorDataBus.get().removeListener(this);writer.shutdown();}
    private static String csv(String s){return s==null?"":s.replace(",","_").replace("\n","_").replace("\r","_");}
}
