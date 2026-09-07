package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FrequencyLanePolicyTest {
    @Test public void subSpeakerCrankOrderIsMonitoredButNotCancelledByDefault() {
        assertTrue(FrequencyLanePolicy.cancellable(23.8, 20.0, 200.0));
        assertFalse(FrequencyLanePolicy.cancellable(11.9, 20.0, 200.0));
    }

    @Test public void nearbyLanesShareOnePhysicalOwner() {
        assertTrue(FrequencyLanePolicy.collide(34.30, 34.80));
        assertFalse(FrequencyLanePolicy.collide(34.30, 35.30));
    }

    @Test public void rejectedStrongLaneRetriesAfterAConservativePause() {
        assertFalse(FrequencyLanePolicy.controllerRetryDue("IDLE", 1_000, 8_999));
        assertTrue(FrequencyLanePolicy.controllerRetryDue("IDLE", 1_000, 9_000));
        assertFalse(FrequencyLanePolicy.controllerRetryDue("RUNNING", 1_000, 20_000));
    }
}
