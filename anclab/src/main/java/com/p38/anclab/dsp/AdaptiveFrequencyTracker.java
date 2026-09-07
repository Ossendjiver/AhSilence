package com.p38.anclab.dsp;

/**
 * Lightweight two-model Kalman bank for a tone's frequency and drift rate.
 * A quiet model rejects jitter while an agile model follows a genuine speed/order change;
 * innovation likelihood blends the two estimates without a hard mode switch.
 */
public final class AdaptiveFrequencyTracker {
    private static final double MIN_DT = 0.05;
    private static final double MAX_DT = 1.0;
    private static final double MEASUREMENT_VARIANCE = 0.04 * 0.04;
    private final Model quiet = new Model(0.0025);
    private final Model agile = new Model(0.16);
    private double quietProbability = 0.85;
    private long lastUpdateMs;
    private boolean initialized;

    public synchronized void reset(double frequencyHz, long nowMs) {
        quiet.reset(frequencyHz);
        agile.reset(frequencyHz);
        quietProbability = 0.85;
        lastUpdateMs = nowMs;
        initialized = true;
    }

    public synchronized double update(double measuredHz, long nowMs) {
        if (!Double.isFinite(measuredHz)) return estimateHz();
        if (!initialized) {
            reset(measuredHz, nowMs);
            return measuredHz;
        }
        double dt = Math.max(MIN_DT, Math.min(MAX_DT, (nowMs - lastUpdateMs) / 1000.0));
        lastUpdateMs = nowMs;

        quiet.predict(dt);
        agile.predict(dt);
        double centre = estimateHz();
        double maximumInnovation = Math.max(1.2, 0.035 * Math.max(8.0, centre));
        if (Math.abs(measuredHz - centre) > maximumInnovation) return centre;

        double quietPrior = 0.985 * quietProbability + 0.035 * (1.0 - quietProbability);
        double agilePrior = 1.0 - quietPrior;
        double quietLikelihood = quiet.update(measuredHz, MEASUREMENT_VARIANCE);
        double agileLikelihood = agile.update(measuredHz, MEASUREMENT_VARIANCE);
        double normalizer = quietPrior * quietLikelihood + agilePrior * agileLikelihood;
        if (normalizer > 1.0e-18) {
            quietProbability = quietPrior * quietLikelihood / normalizer;
            quietProbability = Math.max(0.02, Math.min(0.98, quietProbability));
        }
        return estimateHz();
    }

    public synchronized double estimateHz() {
        if (!initialized) return Double.NaN;
        return quietProbability * quiet.frequency + (1.0 - quietProbability) * agile.frequency;
    }

    public synchronized double driftHzPerSecond() {
        if (!initialized) return 0;
        return quietProbability * quiet.rate + (1.0 - quietProbability) * agile.rate;
    }

    public synchronized double agileProbability() { return 1.0 - quietProbability; }

    private static final class Model {
        final double processNoise;
        double frequency;
        double rate;
        double p00;
        double p01;
        double p10;
        double p11;

        Model(double processNoise) { this.processNoise = processNoise; }

        void reset(double value) {
            frequency = value;
            rate = 0;
            p00 = 0.08;
            p01 = p10 = 0;
            p11 = 0.12;
        }

        void predict(double dt) {
            frequency += rate * dt;
            double old00 = p00;
            double old01 = p01;
            double old10 = p10;
            double old11 = p11;
            double dt2 = dt * dt;
            double dt3 = dt2 * dt;
            p00 = old00 + dt * (old01 + old10) + dt2 * old11
                    + processNoise * dt3 / 3.0;
            p01 = old01 + dt * old11 + processNoise * dt2 / 2.0;
            p10 = old10 + dt * old11 + processNoise * dt2 / 2.0;
            p11 = old11 + processNoise * dt;
        }

        double update(double measurement, double measurementVariance) {
            double innovation = measurement - frequency;
            double innovationVariance = Math.max(1.0e-9, p00 + measurementVariance);
            double k0 = p00 / innovationVariance;
            double k1 = p10 / innovationVariance;
            frequency += k0 * innovation;
            rate += k1 * innovation;
            double old00 = p00;
            double old01 = p01;
            p00 = Math.max(1.0e-9, (1.0 - k0) * old00);
            p01 = (1.0 - k0) * old01;
            p10 = p10 - k1 * old00;
            p11 = Math.max(1.0e-9, p11 - k1 * old01);
            double exponent = -0.5 * innovation * innovation / innovationVariance;
            return Math.exp(Math.max(-60.0, exponent)) / Math.sqrt(innovationVariance);
        }
    }
}
