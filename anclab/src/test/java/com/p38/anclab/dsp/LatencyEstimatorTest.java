package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class LatencyEstimatorTest {
    @Test public void findsDelayedBroadbandSequence() {
        int rate = 1000;
        int delay = 137;
        float[] output = new float[2500];
        float[] input = new float[2500];
        long state = 0x1234abcdL;
        for (int i = 200; i < 1600; i++) {
            state = (state * 1664525L + 1013904223L) & 0xffffffffL;
            output[i] = (float) (((state >>> 8) / (double) 0x00ffffffL) * 2.0 - 1.0);
            input[i + delay] = output[i] * 0.42f;
        }
        LatencyEstimator.Estimate result = LatencyEstimator.estimate(output, input,
                200, 1600, rate, 500);
        assertEquals(137.0, result.latencyMs(), 0.01);
        assertTrue(result.confidence() > 0.99);
    }
}
