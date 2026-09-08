package com.p38.anclab.sensors;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class SensorFusionPolicyTest {
    @Test public void legacyRemainsPrimaryUntilBothSidedAcousticPathsAndStableOutputsAreComplete(){
        List<AncSensorDefinition> s=new ArrayList<>();
        addInput(s,AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.LEFT,"rp2040-main");
        addInput(s,AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.LEFT,"rp2040-main");
        addOutput(s,AncSensorDefinition.Side.LEFT,0.95);
        assertEquals(SensorFusionPolicy.ControllerPriority.LEGACY_PRIMARY,SensorFusionPolicy.priority(s));
        addInput(s,AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.RIGHT,"rp2040-main");
        addInput(s,AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.RIGHT,"rp2040-main");
        addOutput(s,AncSensorDefinition.Side.RIGHT,0.95);
        assertEquals(SensorFusionPolicy.ControllerPriority.SENSOR_PRIMARY,SensorFusionPolicy.priority(s));
    }

    @Test public void mismatchedInputClockOrJitteryOutputBlocksSensorPrimary(){
        List<AncSensorDefinition> s=new ArrayList<>();
        addInput(s,AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.LEFT,"rp2040-main");
        addInput(s,AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.LEFT,"rp2040-main");
        addInput(s,AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.RIGHT,"rp2040-other");
        addInput(s,AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.RIGHT,"rp2040-main");
        addOutput(s,AncSensorDefinition.Side.LEFT,0.95);addOutput(s,AncSensorDefinition.Side.RIGHT,0.40);
        assertEquals(SensorFusionPolicy.ControllerPriority.LEGACY_PRIMARY,SensorFusionPolicy.priority(s));
    }

    private static void addInput(List<AncSensorDefinition> list,AncSensorDefinition.Type type,AncSensorDefinition.Side side,String clock){
        AncSensorDefinition s=new AncSensorDefinition();s.id=type+"-"+side;s.type=type;s.side=side;s.clockDomain=clock;s.enabled=true;s.connected=true;s.calibrationState=AncSensorDefinition.CalibrationState.CALIBRATED;list.add(s);
    }
    private static void addOutput(List<AncSensorDefinition> list,AncSensorDefinition.Side side,double confidence){
        AncSensorDefinition s=new AncSensorDefinition();s.id="out-"+side;s.type=AncSensorDefinition.Type.OUTPUT_CHANNEL;s.side=side;s.enabled=true;s.connected=true;s.outputRoute=AncSensorDefinition.OutputRoute.AUX;s.latencyConfidence=confidence;s.calibrationState=AncSensorDefinition.CalibrationState.CALIBRATED;list.add(s);
    }
}
