package com.p38.anclab.dsp;

/** Normalized cross-correlation delay estimate for a known emitted sequence. */
public final class LatencyEstimator {
    public record Estimate(double latencyMs, double confidence) { }
    private LatencyEstimator() { }

    public static Estimate estimate(float[] output, float[] input, int start, int end,
                                    int sampleRateHz, int maximumLagSamples) {
        int count = Math.min(output.length, input.length);
        start = Math.max(0, start);
        end = Math.min(count, Math.max(start, end));
        int maximumLag = Math.max(0, Math.min(maximumLagSamples, count - end - 1));
        double best = 0;
        int bestLag = 0;
        for (int lag = 0; lag <= maximumLag; lag++) {
            double xy = 0;
            double xx = 1.0e-18;
            double yy = 1.0e-18;
            for (int i = start; i < end; i++) {
                double x = output[i];
                double y = input[i + lag];
                xy += x * y;
                xx += x * x;
                yy += y * y;
            }
            double correlation = Math.abs(xy / Math.sqrt(xx * yy));
            if (correlation > best) { best = correlation; bestLag = lag; }
        }
        return new Estimate(bestLag * 1000.0 / Math.max(1, sampleRateHz), best);
    }
}
