package com.p38.anclab.spl;

/** Converts unweighted digital RMS into calibrated or relative SPL statistics. */
public final class SplMeter {
    private double calibrationOffsetDb = 0.0;
    private boolean calibrated;
    private double energySum;
    private long sampleCount;
    private double maximum = Double.NEGATIVE_INFINITY;

    public synchronized void setCalibrationOffset(double offsetDb, boolean calibrated) {
        calibrationOffsetDb = offsetDb;
        this.calibrated = calibrated;
        resetStatistics();
    }

    public synchronized double update(double broadbandDbFs) { return update(broadbandDbFs,1); }

    /** Adds a block using its sample count so Leq is independent of callback size. */
    public synchronized double update(double broadbandDbFs, long samples) {
        if (!Double.isFinite(broadbandDbFs)) return Double.NaN;
        double spl = broadbandDbFs + calibrationOffsetDb;
        long weight=Math.max(1,samples);
        energySum += Math.pow(10.0, spl / 10.0)*weight;
        sampleCount+=weight;
        maximum = Math.max(maximum, spl);
        return spl;
    }

    public synchronized void calibrateAgainst(double referenceSplDb, double selectedMicDbFs) {
        setCalibrationOffset(referenceSplDb - selectedMicDbFs, true);
    }

    public synchronized double leq() { return sampleCount == 0 ? Double.NaN : 10.0 * Math.log10(energySum / sampleCount); }
    public synchronized double maximum() { return sampleCount == 0 ? Double.NaN : maximum; }
    public synchronized double offsetDb() { return calibrationOffsetDb; }
    public synchronized boolean calibrated() { return calibrated; }
    public synchronized void resetStatistics() { energySum = 0; sampleCount = 0; maximum = Double.NEGATIVE_INFINITY; }
}
