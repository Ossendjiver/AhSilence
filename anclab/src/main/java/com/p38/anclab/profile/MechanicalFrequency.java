package com.p38.anclab.profile;

import com.p38.anclab.telemetry.TelemetryState;

import java.util.Locale;

/** One editable mechanical frequency model anchored to a measured vehicle value. */
public record MechanicalFrequency(
        String id,
        String name,
        double frequencyHz,
        double detectedNumber,
        SourceType detectedNumberType,
        boolean enabled
) {
    public enum SourceType {
        FIXED("Fixed frequency"),
        BEST_SPEED("Best speed"),
        GPS_SPEED("GPS speed"),
        OBD_SPEED("OBD speed"),
        RPM("Engine RPM"),
        ENGINE_LOAD("Engine load"),
        THROTTLE("Throttle");

        private final String label;
        SourceType(String label) { this.label = label; }
        @Override public String toString() { return label; }

        public static SourceType parse(String value) {
            if (value != null) try { return valueOf(value.toUpperCase(Locale.US)); }
            catch (IllegalArgumentException ignored) { }
            return FIXED;
        }
    }

    public double sourceValue(TelemetryState telemetry) {
        if (telemetry == null) return Double.NaN;
        return switch (detectedNumberType) {
            case FIXED -> detectedNumber;
            case BEST_SPEED -> telemetry.bestSpeedKmh();
            case GPS_SPEED -> telemetry.gpsSpeedKmh();
            case OBD_SPEED -> telemetry.obdSpeedKmh();
            case RPM -> telemetry.engineRpm();
            case ENGINE_LOAD -> telemetry.engineLoadPercent();
            case THROTTLE -> telemetry.throttlePercent();
        };
    }

    public double predictedFrequencyHz(TelemetryState telemetry) {
        if (detectedNumberType == SourceType.FIXED || !Double.isFinite(detectedNumber)
                || detectedNumber <= 0) return frequencyHz;
        double current = sourceValue(telemetry);
        if (!Double.isFinite(current) || current < 0) return Double.NaN;
        return frequencyHz * current / detectedNumber;
    }
}
