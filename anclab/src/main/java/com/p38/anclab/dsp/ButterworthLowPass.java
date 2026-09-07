package com.p38.anclab.dsp;

/** Allocation-free eighth-order Butterworth low-pass for safe large-ratio decimation. */
public final class ButterworthLowPass {
    private static final double[] Q = {0.5097955791, 0.6013448869, 0.8999762231, 2.5629154477};
    private final Biquad[] sections = new Biquad[Q.length];

    public ButterworthLowPass(double sampleRateHz, double cutoffHz) {
        if (!(sampleRateHz > 0) || !(cutoffHz > 0) || cutoffHz >= sampleRateHz * 0.5)
            throw new IllegalArgumentException("invalid low-pass frequency");
        for (int i = 0; i < sections.length; i++) sections[i] = new Biquad(sampleRateHz, cutoffHz, Q[i]);
    }

    public double process(double input) {
        double value = input;
        for (Biquad section : sections) value = section.process(value);
        return value;
    }

    public void reset() { for (Biquad section : sections) section.reset(); }

    private static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double z1, z2;

        Biquad(double sampleRate, double cutoff, double q) {
            double omega = 2.0 * Math.PI * cutoff / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            double a0 = 1.0 + alpha;
            b0 = (1.0 - cosine) * 0.5 / a0;
            b1 = (1.0 - cosine) / a0;
            b2 = b0;
            a1 = -2.0 * cosine / a0;
            a2 = (1.0 - alpha) / a0;
        }

        double process(double input) {
            double output = b0 * input + z1;
            z1 = b1 * input - a1 * output + z2;
            z2 = b2 * input - a2 * output;
            return output;
        }

        void reset() { z1 = z2 = 0; }
    }
}
