package com.p38.anclab.dsp;

/** Converts the measured route-calibration FIR plus transport delay into a complex narrowband path. */
public final class SecondaryPathFrequencyResponse {
    private SecondaryPathFrequencyResponse() { }

    public static Complex at(float[] fir, int delaySamples, int sampleRateHz, double frequencyHz) {
        if (fir == null || fir.length == 0 || sampleRateHz <= 0 || !Double.isFinite(frequencyHz))
            return Complex.ZERO;
        double omega = 2.0 * Math.PI * frequencyHz / sampleRateHz;
        double real = 0.0, imaginary = 0.0;
        for (int k = 0; k < fir.length; k++) {
            double phase = -omega * (Math.max(0, delaySamples) + k);
            real += fir[k] * Math.cos(phase);
            imaginary += fir[k] * Math.sin(phase);
        }
        return new Complex(real, imaginary);
    }
}
