package com.p38.anclab.sensors;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class SensorFusionPolicyTest {
    @Test public void legacyRemainsPrimaryUntilBothStereoPathsAreComplete(){
        List<AncSensorDefinition> s=new ArrayList<>();
        add(s,AncSensorDefinition.Type.ADXL345,AncSensorDefinition.Side.LEFT);
        add(s,AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.LEFT);
        add(s,AncSensorDefinition.Type.OUTPUT_CHANNEL,AncSensorDefinition.Side.LEFT);
        assertEquals(SensorFusionPolicy.ControllerPriority.LEGACY_PRIMARY,SensorFusionPolicy.priority(s));
        add(s,AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.RIGHT);
        add(s,AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.RIGHT);
        add(s,AncSensorDefinition.Type.OUTPUT_CHANNEL,AncSensorDefinition.Side.RIGHT);
        assertEquals(SensorFusionPolicy.ControllerPriority.SENSOR_PRIMARY,SensorFusionPolicy.priority(s));
    }

    private static void add(List<AncSensorDefinition> list,AncSensorDefinition.Type type,AncSensorDefinition.Side side){
        AncSensorDefinition s=new AncSensorDefinition();s.id=type+"-"+side;s.type=type;s.side=side;s.enabled=true;s.connected=true;s.calibrationState=AncSensorDefinition.CalibrationState.CALIBRATED;list.add(s);
    }
}
