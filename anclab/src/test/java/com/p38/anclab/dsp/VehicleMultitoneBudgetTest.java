package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VehicleMultitoneBudgetTest {
    @Test public void singleLaneRetainsFullSelectedAuthority() {
        assertEquals(0.04, VehicleNarrowbandBank.perLaneGainLimit(0.08f, 0.5f, 1), 1.0e-8);
    }

    @Test public void tenLanesReceiveMoreThanOldEqualSplitWithoutRaisingHardCeiling() {
        double limit = VehicleNarrowbandBank.perLaneGainLimit(0.08f, 0.5f, 10);
        assertTrue(limit > 0.0090);
        assertTrue(limit < 0.0092);
        assertTrue(limit > 2.25 * 0.004);
    }
}
