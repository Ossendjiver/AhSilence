package com.p38.anclab.sensors;

import java.util.List;

/** Decides whether future feed-forward sensors are authoritative or the existing ANC remains primary. */
public final class SensorFusionPolicy {
    public enum ControllerPriority { LEGACY_PRIMARY, SENSOR_PRIMARY }
    private SensorFusionPolicy(){}

    public static ControllerPriority priority(List<AncSensorDefinition> sensors){
        return stereoReady(sensors)?ControllerPriority.SENSOR_PRIMARY:ControllerPriority.LEGACY_PRIMARY;
    }

    /**
     * Sensor-primary stereo requires, on each side: one calibrated upstream reference (accelerometer
     * or footwell/reference mic), one calibrated error mic, and one calibrated output channel.
     * Until then the existing narrowband/headphone/feedback programs remain fully independent.
     */
    public static boolean stereoReady(List<AncSensorDefinition> sensors){
        return sideReady(sensors,AncSensorDefinition.Side.LEFT)&&sideReady(sensors,AncSensorDefinition.Side.RIGHT);
    }

    private static boolean sideReady(List<AncSensorDefinition> sensors,AncSensorDefinition.Side side){
        boolean ref=false,error=false,out=false;
        if(sensors==null)return false;
        for(AncSensorDefinition s:sensors){
            if(s==null||!s.enabled||!s.connected||s.side!=side||s.calibrationState!=AncSensorDefinition.CalibrationState.CALIBRATED)continue;
            ref|=s.isReference();error|=s.isError();out|=s.isOutput();
        }
        return ref&&error&&out;
    }
}
