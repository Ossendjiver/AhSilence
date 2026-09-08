package com.p38.anclab.sensors;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class SensorFusionPolicyTest {
    @Test public void legacyRemainsPrimaryUntilBothSidedAcousticPathsAndStableOutputsAreComplete(){
        List<AncSensorDefinition> s=new ArrayList<>();
        addInput(s,AncSensorDefinition.Type.ADXL345,AncSensorDefinition.Side.CENTER,"rp2040-main",true);
        addInput(s,AncSensorDefinition.Type.ADXL345,AncSensorDefinition.Side.CENTER,"rp2040-main",true);
        addInput(s,AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.LEFT,"rp2040-main",true);
        addInput(s,AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.LEFT,"rp2040-main",true);
        addOutput(s,AncSensorDefinition.Side.LEFT,0.95,true);
        assertEquals(SensorFusionPolicy.ControllerPriority.LEGACY_PRIMARY,SensorFusionPolicy.priority(s));
        addInput(s,AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.RIGHT,"rp2040-main",true);
        addInput(s,AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.RIGHT,"rp2040-main",true);
        addOutput(s,AncSensorDefinition.Side.RIGHT,0.95,true);
        assertEquals(SensorFusionPolicy.ControllerPriority.SENSOR_PRIMARY,SensorFusionPolicy.priority(s));
        addInput(s,AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.LEFT,"rp2040-main",false); // future optional C-pillar
        assertEquals(SensorFusionPolicy.ControllerPriority.SENSOR_PRIMARY,SensorFusionPolicy.priority(s));
    }

    @Test public void missingRequiredAccelerometerBlocksSensorPrimary(){
        List<AncSensorDefinition> s=complete();
        for(AncSensorDefinition d:s)if(d.type==AncSensorDefinition.Type.ADXL345){d.connected=false;break;}
        assertEquals(SensorFusionPolicy.ControllerPriority.LEGACY_PRIMARY,SensorFusionPolicy.priority(s));
    }

    @Test public void mismatchedInputClockOrJitteryOutputBlocksSensorPrimary(){
        List<AncSensorDefinition> s=complete();
        for(AncSensorDefinition d:s)if(d.type==AncSensorDefinition.Type.REFERENCE_MIC&&d.side==AncSensorDefinition.Side.RIGHT)d.clockDomain="rp2040-other";
        assertEquals(SensorFusionPolicy.ControllerPriority.LEGACY_PRIMARY,SensorFusionPolicy.priority(s));
        s=complete();for(AncSensorDefinition d:s)if(d.isOutput()&&d.side==AncSensorDefinition.Side.RIGHT)d.latencyConfidence=0.40;
        assertEquals(SensorFusionPolicy.ControllerPriority.LEGACY_PRIMARY,SensorFusionPolicy.priority(s));
    }

    private static List<AncSensorDefinition> complete(){
        List<AncSensorDefinition> s=new ArrayList<>();
        addInput(s,AncSensorDefinition.Type.ADXL345,AncSensorDefinition.Side.CENTER,"rp2040-main",true);
        addInput(s,AncSensorDefinition.Type.ADXL345,AncSensorDefinition.Side.CENTER,"rp2040-main",true);
        addInput(s,AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.LEFT,"rp2040-main",true);
        addInput(s,AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.LEFT,"rp2040-main",true);
        addInput(s,AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.RIGHT,"rp2040-main",true);
        addInput(s,AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.RIGHT,"rp2040-main",true);
        addOutput(s,AncSensorDefinition.Side.LEFT,0.95,true);addOutput(s,AncSensorDefinition.Side.RIGHT,0.95,true);return s;
    }

    private static void addInput(List<AncSensorDefinition> list,AncSensorDefinition.Type type,AncSensorDefinition.Side side,String clock,boolean required){
        AncSensorDefinition s=new AncSensorDefinition();s.id=type+"-"+side+"-"+list.size();s.type=type;s.side=side;s.clockDomain=clock;s.enabled=true;s.connected=true;s.requiredForPrimary=required;s.calibrationState=AncSensorDefinition.CalibrationState.CALIBRATED;list.add(s);
    }
    private static void addOutput(List<AncSensorDefinition> list,AncSensorDefinition.Side side,double confidence,boolean required){
        AncSensorDefinition s=new AncSensorDefinition();s.id="out-"+side;s.type=AncSensorDefinition.Type.OUTPUT_CHANNEL;s.side=side;s.enabled=true;s.connected=true;s.requiredForPrimary=required;s.outputRoute=AncSensorDefinition.OutputRoute.AUX;s.latencyConfidence=confidence;s.calibrationState=AncSensorDefinition.CalibrationState.CALIBRATED;list.add(s);
    }
}
