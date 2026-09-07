package com.p38.anclab.dsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Hann-windowed, zero-padded radix-2 FFT used for economical multi-tone discovery. */
public final class Radix2Spectrum {
    public record Peak(double frequencyHz, double amplitude) { }
    private Radix2Spectrum() { }

    public static List<Peak> findPeaks(float[] samples, double sampleRateHz,
                                       double minimumHz, double maximumHz,
                                       int maximumPeaks, double minimumSeparationHz) {
        if (samples.length < 64 || sampleRateHz <= 0 || maximumPeaks <= 0) return List.of();
        int desired = (int) Math.ceil(sampleRateHz / 0.04);
        int fftSize = 1;
        while (fftSize < Math.max(samples.length, desired) && fftSize < 32768) fftSize <<= 1;
        if (fftSize < samples.length) { fftSize = 1; while (fftSize < samples.length) fftSize <<= 1; }
        double[] real = new double[fftSize];
        double[] imaginary = new double[fftSize];
        double windowSum = 0;
        for (int i = 0; i < samples.length; i++) {
            double window = samples.length == 1 ? 1.0 : 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (samples.length - 1));
            real[i] = samples[i] * window;
            windowSum += window;
        }
        transform(real, imaginary);

        int first = Math.max(1, (int) Math.ceil(minimumHz * fftSize / sampleRateHz));
        int last = Math.min(fftSize / 2 - 1,
                (int) Math.floor(Math.min(maximumHz, sampleRateHz * 0.5) * fftSize / sampleRateHz));
        if (last <= first) return List.of();
        double[] amplitudes = new double[last - first + 1];
        double scale = 2.0 / Math.max(1.0, windowSum);
        for (int bin = first; bin <= last; bin++) amplitudes[bin - first] = Math.hypot(real[bin], imaginary[bin]) * scale;
        double[] sorted = amplitudes.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];

        List<Peak> candidates = new ArrayList<>();
        for (int bin = first + 1; bin < last; bin++) {
            double leftAmplitude = amplitudes[bin - first - 1];
            double centreAmplitude = amplitudes[bin - first];
            double rightAmplitude = amplitudes[bin - first + 1];
            if (centreAmplitude <= leftAmplitude || centreAmplitude < rightAmplitude
                    || centreAmplitude <= Math.max(1.0e-5, median * 1.6)) continue;
            double left = Math.log(leftAmplitude + 1.0e-12);
            double centre = Math.log(centreAmplitude + 1.0e-12);
            double right = Math.log(rightAmplitude + 1.0e-12);
            double denominator = left - 2.0 * centre + right;
            double offset = Math.abs(denominator) < 1.0e-12 ? 0
                    : Math.max(-0.5, Math.min(0.5, 0.5 * (left - right) / denominator));
            candidates.add(new Peak((bin + offset) * sampleRateHz / fftSize, centreAmplitude));
        }
        candidates.sort(Comparator.comparingDouble(Peak::amplitude).reversed());
        List<Peak> selected = new ArrayList<>();
        for (Peak candidate : candidates) {
            boolean separated = true;
            for (Peak existing : selected) {
                if (Math.abs(existing.frequencyHz() - candidate.frequencyHz()) < minimumSeparationHz) { separated = false; break; }
            }
            if (!separated) continue;
            selected.add(candidate);
            if (selected.size() == maximumPeaks) break;
        }
        return List.copyOf(selected);
    }

    static void transform(double[] real, double[] imaginary) {
        int n = real.length;
        if (n == 0 || (n & (n - 1)) != 0 || imaginary.length != n)
            throw new IllegalArgumentException("radix-2 FFT requires equal power-of-two arrays");
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) {
                double swap = real[i]; real[i] = real[j]; real[j] = swap;
                swap = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = swap;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2.0 * Math.PI / length;
            double stepReal = Math.cos(angle);
            double stepImaginary = Math.sin(angle);
            for (int start = 0; start < n; start += length) {
                double twiddleReal = 1;
                double twiddleImaginary = 0;
                for (int j = 0; j < length / 2; j++) {
                    int even = start + j;
                    int odd = even + length / 2;
                    double oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary;
                    double oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal;
                    real[odd] = real[even] - oddReal;
                    imaginary[odd] = imaginary[even] - oddImaginary;
                    real[even] += oddReal;
                    imaginary[even] += oddImaginary;
                    double nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary;
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal;
                    twiddleReal = nextReal;
                }
            }
        }
    }
}
