package com.p38.anclab.sensors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe handoff point for present/future hardware adapters. RP2040 adapters should publish the
 * original source sample index and clock domain as well as Android receive time. That preserves
 * synchronous timing through USB/buffering even when the phone delivers packets irregularly.
 * Current ANC can run with this bus empty.
 */
public final class SensorDataBus {
    public record Sample(long timestampNs,long sourceSampleIndex,String clockDomain,float x,float y,float z){}
    public interface Listener { void onSensorSample(String sensorId,Sample sample); }
    private static final SensorDataBus INSTANCE=new SensorDataBus();
    private final Map<String,Sample> latest=new HashMap<>();
    private final List<Listener> listeners=new ArrayList<>();
    private SensorDataBus(){}
    public static SensorDataBus get(){return INSTANCE;}

    /** Compatibility path for Android-local sensors that have no external source clock. */
    public void publish(String sensorId,long timestampNs,float x,float y,float z){publish(sensorId,timestampNs,-1L,"android-monotonic",x,y,z);}

    /** Preferred path for RP2040 frames. timestampNs is Android receive time, sourceSampleIndex is authoritative for relative input timing. */
    public void publish(String sensorId,long timestampNs,long sourceSampleIndex,String clockDomain,float x,float y,float z){
        if(sensorId==null||sensorId.isBlank())return;
        Sample sample=new Sample(timestampNs,sourceSampleIndex,clockDomain==null?"":clockDomain,x,y,z);List<Listener> copy;
        synchronized(this){latest.put(sensorId,sample);copy=new ArrayList<>(listeners);}
        for(Listener l:copy)try{l.onSensorSample(sensorId,sample);}catch(RuntimeException ignored){}
    }
    public synchronized void addListener(Listener l){if(l!=null&&!listeners.contains(l))listeners.add(l);}
    public synchronized void removeListener(Listener l){listeners.remove(l);}
    public synchronized Sample latest(String sensorId){return latest.get(sensorId);}
    public synchronized Map<String,Sample> snapshot(){return Collections.unmodifiableMap(new HashMap<>(latest));}
    public synchronized void clear(){latest.clear();}
}
