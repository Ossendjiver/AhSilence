package com.p38.anclab.telemetry;

/** Immutable GPS/OBD snapshot used by the recovered vehicle mechanical models. */
public record TelemetryState(
        double gpsSpeedKmh,
        double obdSpeedKmh,
        double engineRpm,
        double engineLoadPercent,
        double throttlePercent,
        float gpsAccuracyMetres,
        String gpsStatus,
        String obdStatus,
        long updatedElapsedMs
) {
    public static TelemetryState empty() {
        return new TelemetryState(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Float.NaN, "GPS off", "OBD disconnected", 0);
    }

    public double bestSpeedKmh() {
        return Double.isFinite(obdSpeedKmh) ? obdSpeedKmh : gpsSpeedKmh;
    }

    public TelemetryState withGps(double speedKmh, float accuracy, String status, long nowMs) {
        return new TelemetryState(speedKmh, obdSpeedKmh, engineRpm, engineLoadPercent,
                throttlePercent, accuracy, status, obdStatus, nowMs);
    }

    public TelemetryState withObd(double speedKmh, double rpm, double load, double throttle,
                                  String status, long nowMs) {
        return new TelemetryState(gpsSpeedKmh, speedKmh, rpm, load, throttle,
                gpsAccuracyMetres, gpsStatus, status, nowMs);
    }
}
