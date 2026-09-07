package com.p38.anclab.dsp;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BroadbandDetectorPersistenceTest {
    @Test
    public void matureCandidateRemainsEligibleOnLaterScans() {
        BroadbandDetector detector = new BroadbandDetector();
        SpectrumAnalyzer.DetectedTone tone = new SpectrumAnalyzer.DetectedTone(34.4, 0.01, -40.0);

        assertTrue(detector.update(List.of(tone), 0L).isEmpty());
        assertTrue(detector.update(List.of(tone), 600L).isEmpty());

        List<BroadbandDetector.Candidate> firstReady = detector.update(List.of(tone), 1200L);
        assertEquals(1, firstReady.size());
        assertEquals(34.4, firstReady.get(0).frequencyHz(), 0.3);

        List<BroadbandDetector.Candidate> reconsidered = detector.update(List.of(tone), 1800L);
        assertEquals(1, reconsidered.size());
        assertEquals(firstReady.get(0).id(), reconsidered.get(0).id());
    }
}
