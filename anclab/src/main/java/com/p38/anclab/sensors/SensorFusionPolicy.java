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
     * Sensor-primary stereo requires, on each side, a calibrated acoustic reference, error mic and
     * repeatably calibrated output route. Shared CENTER accelerometers supplement both sides but do
     * not by themselves replace the sided reference microphones. All acoustic inputs must share the
     * same synchronous RP2040 clock domain. Until then legacy ANC remains fully independent.
     */
    public static boolean stereoReady(List<AncSensorDefinition> sensors){
        return commonAcousticInputClock(sensors)!=null
                &&sideReady(sensors,AncSensorDefinition.Side.LEFT)
                &&sideReady(sensors,AncSensorDefinition.Side.RIGHT);
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
