package com.p38.anclab.dsp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Confirms coherent spectral lines across independent full-band scans before ANC may probe them. */
public final class BroadbandDetector {
    public record Candidate(String id,double frequencyHz,double dbFs,double localFloorDbFs,
                            double prominenceDb,int confirmations) {
        public double score(){
            return dbFs+0.35*Math.min(30.0,Math.max(0.0,prominenceDb))
                    +0.20*Math.min(12,Math.max(1,confirmations));
        }
    }
    private static final long MINIMUM_AGE_MS = 1000;
    private static final long STALE_MS = 2500;
    private static final double MATCH_RADIUS_HZ = 0.90;
    private static final double ABSOLUTE_SANITY_FLOOR_DBFS=-86.0;
    private static final double MINIMUM_PROMINENCE_DB=8.0;
    private final List<Track> tracks = new ArrayList<>();

    public synchronized List<Candidate> update(List<SpectrumAnalyzer.DetectedTone> detections, long nowMs) {
        for (Track track : tracks) track.seenThisScan = false;
        for (SpectrumAnalyzer.DetectedTone detection : detections) {
            if (detection.dbFs()<ABSOLUTE_SANITY_FLOOR_DBFS
                    ||detection.prominenceDb()<MINIMUM_PROMINENCE_DB)continue;
            Track best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (Track track : tracks) {
                double distance = Math.abs(track.tracker.estimateHz() - detection.frequencyHz());
                if (!track.seenThisScan && distance <= MATCH_RADIUS_HZ && distance < bestDistance) {
                    best = track; bestDistance = distance;
                }
            }
            if(best==null){
                best=new Track(detection.frequencyHz(),nowMs);
                best.dbFs=detection.dbFs();best.localFloorDbFs=detection.localFloorDbFs();
                best.prominenceDb=detection.prominenceDb();tracks.add(best);
            }else{
                best.dbFs=0.75*best.dbFs+0.25*detection.dbFs();
                best.localFloorDbFs=0.75*best.localFloorDbFs+0.25*detection.localFloorDbFs();
                best.prominenceDb=0.75*best.prominenceDb+0.25*detection.prominenceDb();
            }
            best.frequencyHz = best.tracker.update(detection.frequencyHz(), nowMs);
            best.confirmations++;
            best.lastSeenMs = nowMs;
            best.seenThisScan = true;
        }
        Iterator<Track> iterator = tracks.iterator();
        while (iterator.hasNext()) if (nowMs - iterator.next().lastSeenMs > STALE_MS) iterator.remove();
        List<Candidate> ready = new ArrayList<>();
        for (Track track : tracks) {
            // Mature candidates are deliberately reconsidered on later scans instead of being
            // emitted only once. If all fallback controller slots were occupied when a strong
            // physical tone first matured, it can therefore still be admitted after capacity
            // becomes available. VehicleNarrowbandBank remains responsible for duplicate,
            // strength and lane-capacity policy.
            if (track.confirmations >= 3 && nowMs - track.firstSeenMs >= MINIMUM_AGE_MS) {
                double bin = Math.rint(track.frequencyHz * 2.0) / 2.0;
                ready.add(new Candidate(String.format(java.util.Locale.US, "broad-%.1f", bin),
                        track.frequencyHz,track.dbFs,track.localFloorDbFs,track.prominenceDb,
                        track.confirmations));
            }
        }
        ready.sort((a,b)->Double.compare(b.score(),a.score()));
        return List.copyOf(ready);
    }

    public synchronized void reset() { tracks.clear(); }

    private static final class Track {
        final long firstSeenMs;
        final AdaptiveFrequencyTracker tracker = new AdaptiveFrequencyTracker();
        long lastSeenMs;
        double frequencyHz;
        double dbFs;
        double localFloorDbFs;
        double prominenceDb;
        int confirmations;
        boolean seenThisScan;
        Track(double frequencyHz, long nowMs) {
            firstSeenMs = nowMs; lastSeenMs = nowMs; this.frequencyHz = frequencyHz;
            tracker.reset(frequencyHz, nowMs);
        }
    }
}
