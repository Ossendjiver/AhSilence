package com.p38.anclab.dsp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Confirms coherent spectral lines across independent full-band scans before ANC may probe them. */
public final class BroadbandDetector {
    public record Candidate(String id, double frequencyHz, double dbFs, int confirmations) { }
    private static final long MINIMUM_AGE_MS = 1000;
    private static final long STALE_MS = 2500;
    private static final double MATCH_RADIUS_HZ = 0.65;
    private final List<Track> tracks = new ArrayList<>();

    public synchronized List<Candidate> update(List<SpectrumAnalyzer.DetectedTone> detections, long nowMs) {
        for (Track track : tracks) track.seenThisScan = false;
        for (SpectrumAnalyzer.DetectedTone detection : detections) {
            if (detection.dbFs() < -72.0) continue;
            Track best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (Track track : tracks) {
                double distance = Math.abs(track.tracker.estimateHz() - detection.frequencyHz());
                if (!track.seenThisScan && distance <= MATCH_RADIUS_HZ && distance < bestDistance) {
                    best = track; bestDistance = distance;
                }
            }
            if (best == null) { best = new Track(detection.frequencyHz(), nowMs); tracks.add(best); }
            best.frequencyHz = best.tracker.update(detection.frequencyHz(), nowMs);
            best.dbFs = detection.dbFs();
            best.confirmations++;
            best.lastSeenMs = nowMs;
            best.seenThisScan = true;
        }
        Iterator<Track> iterator = tracks.iterator();
        while (iterator.hasNext()) if (nowMs - iterator.next().lastSeenMs > STALE_MS) iterator.remove();
        List<Candidate> ready = new ArrayList<>();
        for (Track track : tracks) {
            if (!track.emitted && track.confirmations >= 3 && nowMs - track.firstSeenMs >= MINIMUM_AGE_MS) {
                track.emitted = true;
                double bin = Math.rint(track.frequencyHz * 2.0) / 2.0;
                ready.add(new Candidate(String.format(java.util.Locale.US, "broad-%.1f", bin),
                        track.frequencyHz, track.dbFs, track.confirmations));
            }
        }
        return List.copyOf(ready);
    }

    public synchronized void reset() { tracks.clear(); }

    private static final class Track {
        final long firstSeenMs;
        final AdaptiveFrequencyTracker tracker = new AdaptiveFrequencyTracker();
        long lastSeenMs;
        double frequencyHz;
        double dbFs;
        int confirmations;
        boolean emitted;
        boolean seenThisScan;
        Track(double frequencyHz, long nowMs) {
            firstSeenMs = nowMs; lastSeenMs = nowMs; this.frequencyHz = frequencyHz;
            tracker.reset(frequencyHz, nowMs);
        }
    }
}
