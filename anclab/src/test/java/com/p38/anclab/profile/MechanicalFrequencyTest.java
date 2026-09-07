package com.p38.anclab.profile;

import com.p38.anclab.telemetry.TelemetryState;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class MechanicalFrequencyTest {
    @Test public void speedModelScalesFromDetectedPoint() {
        MechanicalFrequency model = new MechanicalFrequency("prop", "Prop", 34.5, 80,
                MechanicalFrequency.SourceType.BEST_SPEED, true);
        TelemetryState telemetry = new TelemetryState(40, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, 2, "GPS", "OBD off", 0);
        assertEquals(17.25, model.predictedFrequencyHz(telemetry), 0.0001);
    }

    @Test public void rpmModelUsesReferenceRpm() {
        MechanicalFrequency model = new MechanicalFrequency("engine", "Engine", 47.6, 714,
                MechanicalFrequency.SourceType.RPM, true);
        TelemetryState telemetry = new TelemetryState(Double.NaN, Double.NaN, 1428,
                Double.NaN, Double.NaN, Float.NaN, "GPS off", "OBD", 0);
        assertEquals(95.2, model.predictedFrequencyHz(telemetry), 0.0001);
    }
}
