package com.p38.anclab.dsp;

public record SpectrumSnapshot(
        double peakFrequencyHz,
        double peakAmplitude,
        double peakDbFs,
        double contrastDb,
        double targetFrequencyHz,
        Complex targetComplex,
        double targetDbFs,
        double broadbandDbFs,
        double secondsAvailable
) { }
