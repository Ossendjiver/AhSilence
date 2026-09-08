package com.p38.anclab.sensors;

import java.util.ArrayList;
import java.util.List;

/** Applies calibrated timing/gain/bias results to persisted sensor definitions. */
public final class SensorCalibrationCoordinator {
    public enum Stage { INVENTORY, STATIC_SENSOR_CALIBRATION, RP2040_INPUT_VERIFY, MICROPHONE_GAIN, OUTPUT_ROUTE_LATENCY, SECONDARY_PATH, CROSS_CHANNEL_VERIFY, COMPLETE }
    private SensorCalibrationCoordinator(){}

    public static List<String> plan(List<AncSensorDefinition> sensors){
        List<String> p=new ArrayList<>();
        p.add("1. Inventory every RP2040 input and stereo output; verify physical location, ADC/TDM channel, SPI chip-select and left/right mapping.");
        p.add("2. ADXL345: collect six stationary +X/-X/+Y/-Y/+Z/-Z poses and solve bias/scale for each axis.");
        p.add("3. RP2040 input clock: verify both accelerometers and all four microphone channels carry one monotonic RP2040 sample counter; measure any fixed per-channel skew, but do not independently align them to Android time.");
        p.add("4. TLV320ADC5140 microphones: equalise gain using a common acoustic probe and verify polarity/channel order while retaining RP2040 timestamps.");
        p.add("5. Output route: select AUX, wired/wireless Android Auto, direct USB DAC or other route; repeat a known probe at least five times and measure phone/tablet → head-unit → amplifier → speaker → B-pillar latency plus p95 jitter.");
        p.add("6. Stereo secondary path: run low-level decorrelated L/R probes and identify L→L, L→R, R→L and R→R speaker/error-mic paths on the RP2040 time base.");
        p.add("7. Cross-channel verification: confirm crosstalk, repeatability, route signature, head-unit volume/EQ/DSP state and that latency jitter remains within the calibrated confidence envelope.");
        p.add("8. Enable SENSOR_PRIMARY only when both sides have calibrated reference/error channels and a calibrated, repeatable output route. Legacy ANC remains independent fallback/secondary control.");
        return p;
    }

    public static void applyAccelerometer(AncSensorDefinition s,SensorCalibrationEngine.AccelerometerCalibration c){
        if(s==null||c==null||s.type!=AncSensorDefinition.Type.ADXL345)throw new IllegalArgumentException("ADXL345 sensor required");
        s.biasX=c.x().bias();s.biasY=c.y().bias();s.biasZ=c.z().bias();
        s.scaleX=c.x().scale();s.scaleY=c.y().scale();s.scaleZ=c.z().scale();
        s.calibrationState=c.quality()>=0.85?AncSensorDefinition.CalibrationState.CALIBRATED:AncSensorDefinition.CalibrationState.PARTIAL;
        s.calibrationUtcMs=System.currentTimeMillis();
    }

    /** Fixed skew calibration for an input. RP2040-synchronous channels normally require only validation. */
    public static void applyLatency(AncSensorDefinition s,SensorCalibrationEngine.LatencyCalibration c){
        if(s==null||c==null)throw new IllegalArgumentException("sensor/result required");
        s.latencyUs=Math.round(c.lagMs()*1000.0);s.latencyJitterUs=0L;s.latencyConfidence=Math.min(1.0,Math.abs(c.correlation()));
        if(Math.abs(c.correlation())>=0.50)s.calibrationState=AncSensorDefinition.CalibrationState.CALIBRATED;
        else s.calibrationState=AncSensorDefinition.CalibrationState.PARTIAL;
        s.calibrationUtcMs=System.currentTimeMillis();
    }

    public static void applyOutputRouteLatency(AncSensorDefinition output,SensorCalibrationEngine.OutputRouteLatencyCalibration c,AncSensorDefinition.OutputRoute route,String signature){
        if(output==null||c==null||!output.isOutput())throw new IllegalArgumentException("output/result required");
        output.outputRoute=route==null?AncSensorDefinition.OutputRoute.UNASSIGNED:route;
        output.routeSignature=signature==null?"":signature.trim();
        output.latencyUs=c.medianLatencyUs();output.latencyJitterUs=c.p95JitterUs();output.latencyConfidence=c.confidence();
        // A long route may still be usable for periodic/narrowband prediction. Repeatability, not absolute latency, gates calibration.
        output.calibrationState=(output.outputRoute!=AncSensorDefinition.OutputRoute.UNASSIGNED&&c.confidence()>=0.60)
                ?AncSensorDefinition.CalibrationState.CALIBRATED:AncSensorDefinition.CalibrationState.PARTIAL;
        output.calibrationUtcMs=System.currentTimeMillis();
    }

    public static void applyMicrophoneGain(AncSensorDefinition s,SensorCalibrationEngine.MicrophoneCalibration c){
        if(s==null||c==null||(s.type!=AncSensorDefinition.Type.REFERENCE_MIC&&s.type!=AncSensorDefinition.Type.ERROR_MIC))throw new IllegalArgumentException("microphone sensor required");
        s.gain=c.gain();
        // RP2040/TDM microphone channels may legitimately have zero relative skew, so latencyUs!=0 is not required.
        boolean timed=s.isSynchronousRp2040Input()||s.latencyConfidence>=0.50;
        if(c.quality()>=0.75&&timed)s.calibrationState=AncSensorDefinition.CalibrationState.CALIBRATED;
        else s.calibrationState=AncSensorDefinition.CalibrationState.PARTIAL;
        s.calibrationUtcMs=System.currentTimeMillis();
    }
}
