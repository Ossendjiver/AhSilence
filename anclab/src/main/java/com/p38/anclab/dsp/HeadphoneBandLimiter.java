package com.p38.anclab.dsp;

/**
 * Allocation-free band limiter used by the predictive headphone ANC path.
 *
 * Reference path: first-order 15 Hz high-pass + fourth-order 600 Hz Butterworth low-pass.
 * Output / filtered-X path: fourth-order 600 Hz Butterworth low-pass only.
 *
 * The upper cutoff deliberately excludes high-frequency phone/USB/ADC artefacts from both
 * prediction and cancellation output. The lower cutoff removes DC and very slow handling/
 * pressure drift from the adaptive reference without turning the controller into a set of
 * narrowband tonal lanes.
 */
public final class HeadphoneBandLimiter {
    public static final double REFERENCE_HIGH_PASS_HZ = 15.0;
    public static final double LOW_PASS_HZ = 600.0;

    // Q values for a fourth-order Butterworth low-pass implemented as two biquads.
    private static final double[] LP_Q = {0.5411961001, 1.3065629649};

    private final DcBlocker highPass;
    private final Biquad[] lowPass = new Biquad[LP_Q.length];

    /** @param includeHighPass true for microphone reference, false for output/filtered-X. */
    public HeadphoneBandLimiter(double sampleRateHz, boolean includeHighPass) {
        if (!(sampleRateHz > 0.0) || LOW_PASS_HZ >= sampleRateHz * 0.5) {
            throw new IllegalArgumentException("invalid sample rate for headphone band limiter");
        }
        // For y[n] = x[n] - x[n-1] + pole*y[n-1], pole ~= exp(-2*pi*fc/fs).
        highPass = includeHighPass
                ? new DcBlocker(Math.exp(-2.0 * Math.PI * REFERENCE_HIGH_PASS_HZ / sampleRateHz))
                : null;
        for (int i = 0; i < LP_Q.length; i++) {
            lowPass[i] = new Biquad(sampleRateHz, LOW_PASS_HZ, LP_Q[i]);
        }
    }

    public float process(float input) {
        double value = input;
        if (highPass != null) value = highPass.process(value);
        for (Biquad section : lowPass) value = section.process(value);
        return (float)value;
    }

    public void reset() {
        if (highPass != null) highPass.reset();
        for (Biquad section : lowPass) section.reset();
    }

    /**
     * Low-frequency group delay of the fourth-order 600 Hz low-pass, used only to extend
     * the prediction horizon by the small delay we deliberately add to the output path.
     * For a fourth-order Butterworth this is approximately 0.416 * fs/fc samples at DC.
     */
    public static int approximateOutputDelaySamples(double sampleRateHz) {
        return Math.max(1, (int)Math.round(0.416 * sampleRateHz / LOW_PASS_HZ));
    }

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

        void reset() { z1 = z2 = 0.0; }
    }
}
