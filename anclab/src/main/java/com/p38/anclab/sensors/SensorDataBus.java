package com.p38.anclab.sensors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe handoff point for present/future hardware adapters. Timestamps are monotonic
 * nanoseconds at acquisition. Current ANC can run with this bus empty; listeners let independent
 * legacy learning/logging observe sensor data without making the sensors a runtime dependency.
 */
public final class SensorDataBus {
    public record Sample(long timestampNs,float x,float y,float z){}
    public interface Listener { void onSensorSample(String sensorId,Sample sample); }
    private static final SensorDataBus INSTANCE=new SensorDataBus();
    private final Map<String,Sample> latest=new HashMap<>();
    private final List<Listener> listeners=new ArrayList<>();
    private SensorDataBus(){}
    public static SensorDataBus get(){return INSTANCE;}

    public void publish(String sensorId,long timestampNs,float x,float y,float z){
        if(sensorId==null||sensorId.isBlank())return;
        Sample sample=new Sample(timestampNs,x,y,z);List<Listener> copy;
        synchronized(this){latest.put(sensorId,sample);copy=new ArrayList<>(listeners);}
        // Call outside the bus lock so storage/learning listeners never stall other publishers.
        for(Listener l:copy)try{l.onSensorSample(sensorId,sample);}catch(RuntimeException ignored){}
    }
    public synchronized void addListener(Listener l){if(l!=null&&!listeners.contains(l))listeners.add(l);}
    public synchronized void removeListener(Listener l){listeners.remove(l);}
    public synchronized Sample latest(String sensorId){return latest.get(sensorId);}
    public synchronized Map<String,Sample> snapshot(){return Collections.unmodifiableMap(new HashMap<>(latest));}
    public synchronized void clear(){latest.clear();}
}
