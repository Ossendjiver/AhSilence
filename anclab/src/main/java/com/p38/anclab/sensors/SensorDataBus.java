package com.p38.anclab.sensors;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe handoff point for future hardware adapters. Timestamps are monotonic nanoseconds at
 * acquisition. Current ANC can run with this bus empty; when calibrated stereo references/errors are
 * present, a sensor-primary controller can consume the same frames while legacy controllers remain
 * available as secondary/fallback layers.
 */
public final class SensorDataBus {
    public record Sample(long timestampNs,float x,float y,float z){}
    private static final SensorDataBus INSTANCE=new SensorDataBus();
    private final Map<String,Sample> latest=new HashMap<>();
    private SensorDataBus(){}
    public static SensorDataBus get(){return INSTANCE;}
    public synchronized void publish(String sensorId,long timestampNs,float x,float y,float z){if(sensorId!=null&&!sensorId.isBlank())latest.put(sensorId,new Sample(timestampNs,x,y,z));}
    public synchronized Sample latest(String sensorId){return latest.get(sensorId);}
    public synchronized Map<String,Sample> snapshot(){return Collections.unmodifiableMap(new HashMap<>(latest));}
    public synchronized void clear(){latest.clear();}
}
