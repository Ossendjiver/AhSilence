package com.p38.anclab.dsp;

/** Shared ownership and speaker-capability policy for vehicle narrowband lanes. */
public final class FrequencyLanePolicy {
    public static final double MONITOR_MINIMUM_HZ = 8.0;
    public static final double MONITOR_MAXIMUM_HZ = 200.0;
    public static final double DEFAULT_CANCELLATION_MINIMUM_HZ = 20.0;
    public static final double COLLISION_RADIUS_HZ = 0.85;

    private FrequencyLanePolicy() { }

    public static double telemetryTrackingRadius(double predictedHz) {
        return Math.min(1.20, Math.max(0.35, Math.abs(predictedHz) * 0.04));
    }

    public static boolean collide(double firstHz, double secondHz) {
        return Double.isFinite(firstHz) && Double.isFinite(secondHz)
                && Math.abs(firstHz - secondHz) < COLLISION_RADIUS_HZ;
    }

    public static boolean cancellable(double frequencyHz, double minimumHz, double maximumHz) {
        return Double.isFinite(frequencyHz) && frequencyHz >= minimumHz && frequencyHz <= maximumHz;
    }
}
