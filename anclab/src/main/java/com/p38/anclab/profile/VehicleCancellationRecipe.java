package com.p38.anclab.profile;

import com.p38.anclab.dsp.Complex;

import java.util.Locale;

/** A reusable speaker-to-microphone path observation for one physical operating bin. */
public record VehicleCancellationRecipe(
        String routeKey,
        String modelId,
        MechanicalFrequency.SourceType sourceType,
        double sourceBin,
        double frequencyHz,
        double secondaryReal,
        double secondaryImag,
        double improvementDb,
        int observations,
        long updatedUtcMs
) {
    public VehicleCancellationRecipe {
        routeKey = routeKey == null ? "" : routeKey;
        modelId = modelId == null ? "" : modelId;
        sourceType = sourceType == null ? MechanicalFrequency.SourceType.FIXED : sourceType;
        observations = Math.max(1, observations);
    }

    public Complex secondaryPath() { return new Complex(secondaryReal, secondaryImag); }

    public String key() {
        return routeKey + "|" + modelId + "|" + sourceType.name() + "|"
                + String.format(Locale.US, "%.3f", sourceBin);
    }

    public static double quantizeSource(MechanicalFrequency.SourceType type,
                                        double sourceValue, double frequencyHz) {
        MechanicalFrequency.SourceType safe = type == null
                ? MechanicalFrequency.SourceType.FIXED : type;
        double value = Double.isFinite(sourceValue) ? sourceValue : frequencyHz;
        double width = switch (safe) {
            case RPM -> 50.0;
            case BEST_SPEED, GPS_SPEED, OBD_SPEED -> 2.0;
            case ENGINE_LOAD, THROTTLE -> 5.0;
            case FIXED -> 0.5;
        };
        return Math.rint(value / width) * width;
    }
}
