package com.p38.anclab.dsp;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class VehicleMonitorOnlyCapacityTest {
    @Test
    public void monitorOnlyDiscoveryDoesNotConsumeControllerSlot() throws Exception {
        VehicleNarrowbandBank bank = new VehicleNarrowbandBank(List.of(), null, 0.08f, 0.5f, 20.0, 200.0, "test", List.of());
        Field discoveredField = VehicleNarrowbandBank.class.getDeclaredField("discovered");
        discoveredField.setAccessible(true);
        @SuppressWarnings("unchecked") List<Object> discovered = (List<Object>) discoveredField.get(bank);
        Class<?> laneClass = Class.forName("com.p38.anclab.dsp.VehicleNarrowbandBank$DiscoveredLane");
        Constructor<?> ctor = laneClass.getDeclaredConstructor(String.class, double.class, long.class);
        ctor.setAccessible(true);
        Field cancellable = laneClass.getDeclaredField("cancellable");
        cancellable.setAccessible(true);
        for (int i = 0; i < 7; i++) {
            Object monitor = ctor.newInstance("monitor-" + i, 10.0 + i, 0L);
            cancellable.setBoolean(monitor, false);
            discovered.add(monitor);
        }
        for (int i = 0; i < 3; i++) {
            Object controller = ctor.newInstance("controller-" + i, 30.0 + i * 2.0, 0L);
            cancellable.setBoolean(controller, true);
            discovered.add(controller);
        }
        Method count = VehicleNarrowbandBank.class.getDeclaredMethod("discoveredControllerSlotCount");
        count.setAccessible(true);
        assertEquals(3, ((Integer) count.invoke(bank)).intValue());
    }
}
