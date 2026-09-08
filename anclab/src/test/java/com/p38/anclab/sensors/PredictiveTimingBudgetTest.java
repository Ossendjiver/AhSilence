package com.p38.anclab.sensors;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PredictiveTimingBudgetTest {
    @Test public void fastUpstreamReferenceCanEnableTrueBroadbandFeedforward(){
        PredictiveTimingBudget.Result r=PredictiveTimingBudget.evaluate(30000,300,0.95,18000,400,0.95);
        assertTrue(r.previewMarginUs()>0);assertTrue(r.broadbandFeedforwardEligible());assertTrue(r.periodicPredictionEligible());
    }

    @Test public void longStableAndroidRouteAllowsPeriodicButNotRandomBroadbandPrediction(){
        PredictiveTimingBudget.Result r=PredictiveTimingBudget.evaluate(8000,300,0.95,85000,400,0.95);
        assertFalse(r.broadbandFeedforwardEligible());assertTrue(r.periodicPredictionEligible());
    }

    @Test public void jitteryRouteDisablesEvenPeriodicPrediction(){
        PredictiveTimingBudget.Result r=PredictiveTimingBudget.evaluate(10000,2000,0.90,60000,5000,0.90);
        assertFalse(r.broadbandFeedforwardEligible());assertFalse(r.periodicPredictionEligible());
        assertTrue(PredictiveTimingBudget.jitterPhaseDegrees(5000,100)>100.0);
    }
}
