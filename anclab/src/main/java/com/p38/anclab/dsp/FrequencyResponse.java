package com.p38.anclab.dsp;

/** Immutable microphone magnitude correction, deliberately independent of absolute SPL offset. */
@FunctionalInterface
public interface FrequencyResponse {
    FrequencyResponse FLAT = frequencyHz -> 0.0;
    double correctionDb(double frequencyHz);
    default double amplitudeScale(double frequencyHz) {
        double db=correctionDb(frequencyHz);
        return Double.isFinite(db)?Math.pow(10.0,db/20.0):1.0;
    }
}
