package com.p38.anclab.telemetry;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import com.p38.anclab.profile.MechanicalFrequency;
import com.p38.anclab.profile.ProfileStore;
import com.p38.anclab.recording.AppLog;
import com.p38.anclab.storage.AncStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live GPS/OBD source and persistent frequency-learning layer for vehicle ANC.
 *
 * The base mechanical model remains f = fref * x / xref. Acoustic observations around the
 * telemetry prediction learn only a small multiplicative correction, so the controller can
 * fine-tune tyre circumference, driveline ratio, torque-converter slip and imperfect starting
 * anchors without losing the physical order relationship.
 */
public final class VehicleTelemetryRuntime {
    private static final String TAG="VehicleTelemetry";
    private static VehicleTelemetryRuntime instance;

    // P38 highway fallback derived from RAVE: 4th overall high-range ratio 3.13:1 and the
    // near-identical 255/65R16 / 255/55R18 rolling diameters (~0.738 m).
    // This is deliberately only used when real OBD RPM is absent and the vehicle is at a speed
    // where locked 4th is plausible. The acoustic learner then corrects the starting estimate.
    private static final double P38_HIGHWAY_RPM_PER_KMH=22.50;
    private static final double P38_IDLE_RPM=714.0;

    public static synchronized void initialize(Context context, AncStorage storage){
        if(instance==null)instance=new VehicleTelemetryRuntime(context.getApplicationContext(),storage);
    }
    public static synchronized VehicleTelemetryRuntime get(){return instance;}

    private final Context context;
    private final AncStorage storage;
    private final GpsTelemetry gps;
    private final ObdClient obd;
    private volatile TelemetryState telemetry=TelemetryState.empty();
    private volatile String activeProfile=ProfileStore.PROFILE_P38;
    private final Map<String,MechanicalFrequency> models=new LinkedHashMap<>();
    private final Map<String,Learned> learned=new LinkedHashMap<>();
    private volatile long lastTouchElapsedMs=0L;
    private volatile long lastPersistElapsedMs=0L;
    private volatile String obdDevice="";
    private Thread watchdog;

    private VehicleTelemetryRuntime(Context context,AncStorage storage){
        this.context=context;this.storage=storage;
        gps=new GpsTelemetry(context,(speed,accuracy,status,now)->{
            TelemetryState old=telemetry;
            telemetry=old.withGps(speed,accuracy,status,now);
        });
        obd=new ObdClient(context,(speed,rpm,load,throttle,status,now)->{
            TelemetryState old=telemetry;
            telemetry=old.withObd(speed,rpm,load,throttle,status,now);
        });
        watchdog=new Thread(this::watchdogLoop,"ANC-Vehicle-Telemetry-Watchdog");
        watchdog.setDaemon(true);watchdog.start();
    }

    /** Called as a vehicle profile is prepared. Repeated calls are cheap. */
    public synchronized void activateProfile(String profileId,List<MechanicalFrequency> definitions){
        activeProfile=ProfileStore.PROFILE_E46.equals(profileId)?ProfileStore.PROFILE_E46:ProfileStore.PROFILE_P38;
        models.clear();if(definitions!=null)for(MechanicalFrequency m:definitions)if(m!=null&&m.enabled())models.put(m.id(),m);
        loadLearning();touch();
        gps.start();
        autoConnectObd();
        AppLog.i(TAG,"Telemetry active for "+activeProfile+" models="+models.size());
    }

    public void touch(){lastTouchElapsedMs=SystemClock.elapsedRealtime();}

    public TelemetryState telemetry(){touch();return telemetry;}

    /** Current physical-source value used by one model, with conservative P38 RPM estimation. */
    public double sourceValue(MechanicalFrequency model){
        if(model==null)return Double.NaN;
        TelemetryState t=telemetry;
        return switch(model.detectedNumberType()){
            case FIXED -> model.detectedNumber();
            case BEST_SPEED -> t.bestSpeedKmh();
            case GPS_SPEED -> t.gpsSpeedKmh();
            case OBD_SPEED -> t.obdSpeedKmh();
            case RPM -> {
                if(Double.isFinite(t.engineRpm())&&t.engineRpm()>250)yield t.engineRpm();
                yield estimatedP38Rpm(t.bestSpeedKmh());
            }
            case ENGINE_LOAD -> t.engineLoadPercent();
            case THROTTLE -> t.throttlePercent();
        };
    }

    /** Base telemetry prediction before learned acoustic correction. */
    public double basePredictedHz(MechanicalFrequency model){
        if(model==null)return Double.NaN;
        if(model.detectedNumberType()==MechanicalFrequency.SourceType.FIXED||!Double.isFinite(model.detectedNumber())||model.detectedNumber()<=0)return model.frequencyHz();
        double source=sourceValue(model);if(!Double.isFinite(source)||source<0)return Double.NaN;
        return model.frequencyHz()*source/model.detectedNumber();
    }

    /** Telemetry prediction including persistent small acoustic correction. */
    public synchronized double predictedHz(MechanicalFrequency model){
        touch();double base=basePredictedHz(model);if(!Double.isFinite(base))return Double.NaN;
        Learned l=learned.get(model.id());double factor=l==null?1.0:l.correction;
        return base*factor;
    }

    /** Frequencies currently owned by the coherent narrowband bank. */
    public synchronized double[] currentControlledFrequencies(){
        touch();List<Double> values=new ArrayList<>();
        for(MechanicalFrequency m:models.values()){
            double f=predictedHz(m);if(Double.isFinite(f)&&f>=8.0&&f<=200.0)values.add(f);
        }
        double[] out=new double[values.size()];for(int i=0;i<out.length;i++)out[i]=values.get(i);return out;
    }

    public synchronized List<MechanicalFrequency> activeModels(){return new ArrayList<>(models.values());}

    /**
     * Accept a microphone-localised peak near the telemetry prediction. The correction is intentionally
     * bounded to +/-4%; larger disagreement is treated as a different source rather than relearning the
     * mechanical order. Early observations learn quickly; subsequent runs become progressively slower.
     */
    public synchronized void observe(MechanicalFrequency model,double measuredHz,double contrastDb,double peakDbFs){
        if(model==null||!Double.isFinite(measuredHz)||contrastDb<2.2||peakDbFs<-78.0)return;
        double base=basePredictedHz(model);if(!Double.isFinite(base)||base<8.0||base>200.0)return;
        double current=predictedHz(model);if(Math.abs(measuredHz-current)>1.20)return;
        double ratio=measuredHz/base;if(!Double.isFinite(ratio)||ratio<0.96||ratio>1.04)return;
        Learned l=learned.computeIfAbsent(model.id(),k->new Learned());
        double alpha=l.observations<8?0.12:l.observations<30?0.045:0.015;
        l.correction=clamp(l.correction+alpha*(ratio-l.correction),0.96,1.04);
        l.observations++;l.confidence=1.0-Math.exp(-l.observations/12.0);l.lastMeasuredHz=measuredHz;l.lastSourceValue=sourceValue(model);l.updatedUtcMs=System.currentTimeMillis();
        long now=SystemClock.elapsedRealtime();if(l.observations<=3||now-lastPersistElapsedMs>5000){persistLearning();lastPersistElapsedMs=now;}
    }

    public synchronized String status(){
        TelemetryState t=telemetry;double rpm=Double.isFinite(t.engineRpm())?t.engineRpm():estimatedP38Rpm(t.bestSpeedKmh());
        String rpmTag=Double.isFinite(t.engineRpm())?"OBD RPM":"est RPM";
        int learnedCount=0;for(Learned l:learned.values())if(l.observations>0)learnedCount++;
        return String.format(Locale.US,"GPS %s · OBD %s · speed %s · %s %s · learned %d/%d",
                t.gpsStatus(),t.obdStatus(),fmt(t.bestSpeedKmh()),rpmTag,fmt(rpm),learnedCount,models.size());
    }

    private double estimatedP38Rpm(double speedKmh){
        if(!ProfileStore.PROFILE_P38.equals(activeProfile)||!Double.isFinite(speedKmh))return Double.NaN;
        if(speedKmh<=3.0)return P38_IDLE_RPM;
        if(speedKmh>=55.0&&speedKmh<=125.0)return Math.max(P38_IDLE_RPM,speedKmh*P38_HIGHWAY_RPM_PER_KMH);
        return Double.NaN; // lower gears cannot be inferred safely from speed alone
    }

    private void autoConnectObd(){
        if(obd.isRunning())return;
        if(Build.VERSION.SDK_INT>=31&&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            List<ObdClient.DeviceChoice> choices=obd.pairedDevices();ObdClient.DeviceChoice best=null;
            for(ObdClient.DeviceChoice c:choices){String n=c.name()==null?"":c.name().toLowerCase(Locale.US);if(n.contains("obd")||n.contains("elm")||n.contains("vgate")||n.contains("v-link")||n.contains("vlink")||n.contains("veepeak")){best=c;break;}}
            if(best!=null){obdDevice=best.toString();obd.connect(best.address());}
        }catch(Throwable e){AppLog.e(TAG,"OBD auto-connect failed",e);}
    }

    private void watchdogLoop(){
        for(;;){
            try{Thread.sleep(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            long age=SystemClock.elapsedRealtime()-lastTouchElapsedMs;
            if(age>12000){try{gps.stop();}catch(Exception ignored){}try{obd.close();}catch(Exception ignored){}}
        }
    }

    private synchronized void loadLearning(){
        learned.clear();if(storage==null||!storage.isConnected())return;
        try{
            String raw=storage.readText("profiles/"+activeProfile+"/mechanical_learning.json");if(raw==null||raw.isBlank())return;
            JSONObject root=new JSONObject(raw);JSONArray a=root.optJSONArray("models");if(a==null)return;
            for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString("id","");if(id.isEmpty())continue;Learned l=new Learned();l.correction=clamp(o.optDouble("correction",1.0),0.96,1.04);l.observations=o.optInt("observations",0);l.confidence=o.optDouble("confidence",0);l.lastMeasuredHz=o.optDouble("lastMeasuredHz",Double.NaN);l.lastSourceValue=o.optDouble("lastSourceValue",Double.NaN);l.updatedUtcMs=o.optLong("updatedUtcMs",0);learned.put(id,l);}
        }catch(Exception e){AppLog.e(TAG,"Could not read mechanical learning",e);}
    }

    private synchronized void persistLearning(){
        if(storage==null||!storage.isConnected())return;
        try{
            JSONObject root=new JSONObject();root.put("format","anc-lab-mechanical-learning-v1");root.put("profile",activeProfile);root.put("updatedUtcMs",System.currentTimeMillis());JSONArray a=new JSONArray();
            for(Map.Entry<String,Learned> entry:learned.entrySet()){Learned l=entry.getValue();JSONObject o=new JSONObject();o.put("id",entry.getKey());o.put("correction",l.correction);o.put("observations",l.observations);o.put("confidence",l.confidence);o.put("lastMeasuredHz",l.lastMeasuredHz);o.put("lastSourceValue",l.lastSourceValue);o.put("updatedUtcMs",l.updatedUtcMs);a.put(o);}root.put("models",a);
            storage.writeJson("profiles/"+activeProfile+"/mechanical_learning.json",root.toString());
        }catch(Exception e){AppLog.e(TAG,"Could not save mechanical learning",e);}
    }

    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private static String fmt(double v){return Double.isFinite(v)?String.format(Locale.US,"%.0f",v):"—";}
    private static final class Learned {double correction=1.0;int observations=0;double confidence=0,lastMeasuredHz=Double.NaN,lastSourceValue=Double.NaN;long updatedUtcMs=0;}
}
