package com.p38.anclab.dsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cascaded narrow notches used only by the speculative vehicle broadband controller.
 * Predictable engine/shaft/telemetry lanes are handled separately, so the broadband
 * controller must not learn or fight those same components.
 */
public final class PredictableFrequencyExcluder {
    private static final double BANDWIDTH_HZ = 3.0;
    private final double sampleRateHz;
    private Notch[] sections = new Notch[0];
    private double[] centres = new double[0];

    public PredictableFrequencyExcluder(double sampleRateHz, double[] frequenciesHz) {
        if (!(sampleRateHz > 0)) throw new IllegalArgumentException("sample rate must be positive");
        this.sampleRateHz = sampleRateHz;
        setFrequencies(frequenciesHz);
    }

    /** Reconfigure outside the audio hot loop when telemetry/narrowband targets move. */
    public synchronized void setFrequencies(double[] frequenciesHz) {
        if (frequenciesHz == null || frequenciesHz.length == 0) {
            centres = new double[0];
            sections = new Notch[0];
            return;
        }
        double[] sorted = Arrays.copyOf(frequenciesHz, frequenciesHz.length);
        Arrays.sort(sorted);
        List<Double> keep = new ArrayList<>();
        for (double f : sorted) {
            if (!Double.isFinite(f) || f < 15.0 || f > 600.0) continue;
            // Merge almost-identical targets so adjacent telemetry aliases do not create a
            // numerically excessive cascade. Close but distinct engine orders remain separate.
            if (!keep.isEmpty() && Math.abs(keep.get(keep.size()-1) - f) < 0.35) continue;
            keep.add(f);
        }
        centres = new double[keep.size()];
        sections = new Notch[keep.size()];
        for (int i=0;i<keep.size();i++) {
            double f=keep.get(i);
            centres[i]=f;
            double q=Math.max(4.0, Math.min(80.0, f / BANDWIDTH_HZ));
            sections[i]=new Notch(sampleRateHz,f,q);
        }
    }

    public float process(float input) {
        double v=input;
        Notch[] local=sections;
        for (Notch n:local) v=n.process(v);
        return (float)v;
    }

    public synchronized void reset() { for (Notch n:sections) n.reset(); }
    public synchronized double[] centresHz() { return Arrays.copyOf(centres, centres.length); }

    private static final class Notch {
        final double b0,b1,b2,a1,a2;
        double z1,z2;
        Notch(double fs,double f,double q) {
            double w=2.0*Math.PI*f/fs;
            double c=Math.cos(w);
            double alpha=Math.sin(w)/(2.0*q);
            double a0=1.0+alpha;
            b0=1.0/a0;
            b1=(-2.0*c)/a0;
            b2=1.0/a0;
            a1=(-2.0*c)/a0;
            a2=(1.0-alpha)/a0;
        }
        double process(double x) {
            double y=b0*x+z1;
            z1=b1*x-a1*y+z2;
            z2=b2*x-a2*y;
            return y;
        }
        void reset(){z1=z2=0.0;}
    }
}
