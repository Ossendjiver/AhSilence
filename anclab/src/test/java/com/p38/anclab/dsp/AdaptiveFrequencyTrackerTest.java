package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdaptiveFrequencyTrackerTest {
    @Test public void boundedTrackerRejectsMeasurementsOutsidePhysicalNeighbourhood() {
        AdaptiveFrequencyTracker tracker = new AdaptiveFrequencyTracker();
        tracker.setBounds(34.0, 35.0);
        tracker.reset(34.5, 0);

        for (int i = 1; i <= 40; i++) tracker.update(36.0, i * 100L);

        assertEquals(34.5, tracker.estimateHz(), 1.0e-9);
    }

    @Test public void boundedTrackerCanStillFollowValidMotionInsideNeighbourhood() {
        AdaptiveFrequencyTracker tracker = new AdaptiveFrequencyTracker();
        tracker.setBounds(34.0, 35.0);
        tracker.reset(34.5, 0);

        for (int i = 1; i <= 40; i++) tracker.update(34.9, i * 100L);

        assertTrue(tracker.estimateHz() > 34.7);
        assertTrue(tracker.estimateHz() <= 35.0);
    }
}
