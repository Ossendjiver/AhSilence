package com.p38.anclab.dsp;

/** Band-energy backstop which trips after repeated verified worsening and enforces a mute period. */
public final class BroadbandSafetyGuard {
    public enum Decision { OK, COOLDOWN, TRIP }
    private static final double TRIP_RISE_DB = 1.5;
    private static final int TRIP_COUNT = 4;
    private static final long COOLDOWN_MS = 8000;
    private double baselineDb = Double.NaN;
    private int worseningCount;
    private long cooldownUntilMs;

    public synchronized Decision update(double bandDb, boolean outputActive, long nowMs) {
        if (!Double.isFinite(bandDb)) return nowMs < cooldownUntilMs ? Decision.COOLDOWN : Decision.OK;
        if (nowMs < cooldownUntilMs) {
            if (!outputActive) learnBaseline(bandDb);
            return Decision.COOLDOWN;
        }
        if (!outputActive) {
            learnBaseline(bandDb);
            worseningCount = 0;
            return Decision.OK;
        }
        if (!Double.isFinite(baselineDb)) baselineDb = bandDb;
        if (bandDb > baselineDb + TRIP_RISE_DB) worseningCount++;
        else worseningCount = Math.max(0, worseningCount - 1);
        if (worseningCount < TRIP_COUNT) return Decision.OK;
        worseningCount = 0;
        cooldownUntilMs = nowMs + COOLDOWN_MS;
        return Decision.TRIP;
    }

    public synchronized void reset() { baselineDb = Double.NaN; worseningCount = 0; cooldownUntilMs = 0; }
    public synchronized boolean isCoolingDown(long nowMs) { return nowMs < cooldownUntilMs; }
    private void learnBaseline(double bandDb) { baselineDb = Double.isFinite(baselineDb) ? baselineDb * 0.92 + bandDb * 0.08 : bandDb; }
}
