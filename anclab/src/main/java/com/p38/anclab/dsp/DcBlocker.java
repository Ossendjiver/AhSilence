package com.p38.anclab.dsp;

/** One-pole DC blocker suitable for the allocation-free microphone hot loop. */
public final class DcBlocker {
    private final double pole;
    private double previousInput;
    private double previousOutput;

    public DcBlocker(double pole) {
        if (!(pole > 0 && pole < 1)) throw new IllegalArgumentException("pole must be between 0 and 1");
        this.pole = pole;
    }

    public double process(double input) {
        double output = input - previousInput + pole * previousOutput;
        previousInput = input;
        previousOutput = output;
        return output;
    }

    public void reset() { previousInput = 0; previousOutput = 0; }
}
