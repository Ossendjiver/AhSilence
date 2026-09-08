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
     * Sensor-primary stereo requires the complete explicitly-required hardware set, a shared acoustic
     * input clock, and on each side a calibrated reference/error/output path. Later sensors can be
     * added as optional learning channels before being promoted to required status.
     */
    public static boolean stereoReady(List<AncSensorDefinition> sensors){
        return requiredSensorsReady(sensors)
                &&commonAcousticInputClock(sensors)!=null
                &&sideReady(sensors,AncSensorDefinition.Side.LEFT)
                &&sideReady(sensors,AncSensorDefinition.Side.RIGHT);
    }

    public static boolean requiredSensorsReady(List<AncSensorDefinition> sensors){
        if(sensors==null)return false;boolean anyRequired=false;
        for(AncSensorDefinition s:sensors){
            if(s==null||!s.enabled||!s.requiredForPrimary)continue;anyRequired=true;
            if(!s.connected||s.calibrationState!=AncSensorDefinition.CalibrationState.CALIBRATED)return false;
            if(s.isOutput()&&(s.outputRoute==AncSensorDefinition.OutputRoute.UNASSIGNED||s.latencyConfidence<0.60))return false;
        }
        return anyRequired;
    }

    public static String commonAcousticInputClock(List<AncSensorDefinition> sensors){
        String clock=null;if(sensors==null)return null;
        for(AncSensorDefinition s:sensors){
            if(s==null||!s.enabled||!s.connected||(s.type!=AncSensorDefinition.Type.REFERENCE_MIC&&s.type!=AncSensorDefinition.Type.ERROR_MIC))continue;
            if(s.clockDomain==null||s.clockDomain.isBlank())return null;
            if(clock==null)clock=s.clockDomain;else if(!clock.equals(s.clockDomain))return null;
        }
        return clock;
    }

    private static boolean sideReady(List<AncSensorDefinition> sensors,AncSensorDefinition.Side side){
        boolean refMic=false,error=false,out=false;
        if(sensors==null)return false;
        for(AncSensorDefinition s:sensors){
            if(s==null||!s.enabled||!s.connected||s.side!=side||s.calibrationState!=AncSensorDefinition.CalibrationState.CALIBRATED)continue;
            if(s.type==AncSensorDefinition.Type.REFERENCE_MIC)refMic=true;
            if(s.isError())error=true;
            if(s.isOutput()&&s.outputRoute!=AncSensorDefinition.OutputRoute.UNASSIGNED&&s.latencyConfidence>=0.60)out=true;
        }
        return refMic&&error&&out;
    }
}
