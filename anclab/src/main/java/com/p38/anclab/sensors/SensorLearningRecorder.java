package com.p38.anclab.sensors;

import com.p38.anclab.storage.AncStorage;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persists sensor observations as an independent learning stream. Android receive time and the
 * original external sample counter are both retained so later training can separate USB scheduling
 * jitter from genuine mechanical/acoustic timing. Legacy ANC remains operational with no sensors.
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
        String line=String.format(Locale.US,"%d,%d,%s,%s,%.9f,%.9f,%.9f\n",sample.timestampNs(),sample.sourceSampleIndex(),csv(sample.clockDomain()),csv(sensorId),sample.x(),sample.y(),sample.z());
        writer.execute(()->{
            String path="logs/sensors-"+profile+".csv";
            if(!storage.exists(path))storage.appendText(path,"android_receive_ns,source_sample_index,clock_domain,sensor_id,x,y,z\n");
            storage.appendText(path,line);
        });
    }

    public void close(){SensorDataBus.get().removeListener(this);writer.shutdown();}
    private static String csv(String s){return s==null?"":s.replace(",","_").replace("\n","_").replace("\r","_");}
}
