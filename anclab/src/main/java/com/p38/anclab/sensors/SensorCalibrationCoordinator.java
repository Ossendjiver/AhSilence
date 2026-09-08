package com.p38.anclab.sensors;

import java.util.ArrayList;
import java.util.List;

/** Applies calibrated timing/gain/bias results to persisted sensor definitions. */
public final class SensorCalibrationCoordinator {
    public enum Stage { INVENTORY, STATIC_SENSOR_CALIBRATION, CLOCK_ALIGNMENT, MICROPHONE_GAIN, OUTPUT_LATENCY, CROSS_CHANNEL_VERIFY, COMPLETE }
    private SensorCalibrationCoordinator(){}

    public static List<String> plan(List<AncSensorDefinition> sensors){
        List<String> p=new ArrayList<>();
        p.add("1. Inventory and identify every physical sensor/output; verify left/right location and transport.");
        p.add("2. ADXL345: collect six stationary +X/-X/+Y/-Y/+Z/-Z poses and solve bias/scale for each axis.");
        p.add("3. Clock alignment: record a shared mechanical/acoustic event and estimate each sensor's offset to the app monotonic clock.");
        p.add("4. Reference/error microphones: align latency, equalise channel gain with a common acoustic probe, then check polarity.");
        p.add("5. Outputs: run low-level decorrelated L/R probes and measure output-to-each-error-mic latency and secondary path.");
        p.add("6. Cross-channel verification: confirm left/right mapping, crosstalk, repeatability and no route/volume changes.");
        p.add("7. Enable SENSOR_PRIMARY only when each side has a calibrated reference, calibrated error mic and calibrated output.");
        return p;
    }

    public static void applyAccelerometer(AncSensorDefinition s,SensorCalibrationEngine.AccelerometerCalibration c){
        if(s==null||c==null||s.type!=AncSensorDefinition.Type.ADXL345)throw new IllegalArgumentException("ADXL345 sensor required");
        s.biasX=c.x().bias();s.biasY=c.y().bias();s.biasZ=c.z().bias();
        s.scaleX=c.x().scale();s.scaleY=c.y().scale();s.scaleZ=c.z().scale();
        s.calibrationState=c.quality()>=0.85?AncSensorDefinition.CalibrationState.CALIBRATED:AncSensorDefinition.CalibrationState.PARTIAL;
        s.calibrationUtcMs=System.currentTimeMillis();
    }

    public static void applyLatency(AncSensorDefinition s,SensorCalibrationEngine.LatencyCalibration c){
        if(s==null||c==null)throw new IllegalArgumentException("sensor/result required");
        s.latencyUs=Math.round(c.lagMs()*1000.0);
        if(Math.abs(c.correlation())>=0.50)s.calibrationState=AncSensorDefinition.CalibrationState.CALIBRATED;
        else s.calibrationState=AncSensorDefinition.CalibrationState.PARTIAL;
        s.calibrationUtcMs=System.currentTimeMillis();
    }

    public static void applyMicrophoneGain(AncSensorDefinition s,SensorCalibrationEngine.MicrophoneCalibration c){
        if(s==null||c==null||(s.type!=AncSensorDefinition.Type.REFERENCE_MIC&&s.type!=AncSensorDefinition.Type.ERROR_MIC))throw new IllegalArgumentException("microphone sensor required");
        s.gain=c.gain();
        if(c.quality()>=0.75&&s.latencyUs!=0)s.calibrationState=AncSensorDefinition.CalibrationState.CALIBRATED;
        else s.calibrationState=AncSensorDefinition.CalibrationState.PARTIAL;
        s.calibrationUtcMs=System.currentTimeMillis();
    }
}
