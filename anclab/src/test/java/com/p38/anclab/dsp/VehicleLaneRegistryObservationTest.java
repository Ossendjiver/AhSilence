package com.p38.anclab.dsp;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VehicleLaneRegistryObservationTest {
    @Test public void persistentObservationIsVisibleWithoutCancellationLane(){
        VehicleLaneRegistry.publish(List.of(),List.of(new VehicleLaneRegistry.ObservedTone(
                "broad-85.0",84.96,-50.0,38.0,8,"unowned candidate")));
        assertEquals(0,VehicleLaneRegistry.monitoredCount());
        assertEquals(1,VehicleLaneRegistry.observedCount());
        assertEquals(0,VehicleLaneRegistry.activeCount());
        assertTrue(VehicleLaneRegistry.summary().contains("84.96 Hz"));
        assertTrue(VehicleLaneRegistry.summary().contains("unowned candidate"));
        VehicleLaneRegistry.clear();
    }
}
