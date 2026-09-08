package com.p38.anclab.telemetry;

import com.p38.anclab.profile.MechanicalFrequency;

public class VehicleTelemetryRuntime {
    public void touch() { }
    public double predictedHz(MechanicalFrequency model) { return Double.NaN; }
    public void observe(MechanicalFrequency model,double hz,double contrast,double db) { }
    public String status() { return ""; }
    public double sourceValue(MechanicalFrequency model) { return Double.NaN; }
}
