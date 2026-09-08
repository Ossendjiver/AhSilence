package com.p38.anclab.sensors;

/**
 * Timing eligibility for future sensor-primary ANC.
 * referenceLeadUs is how much earlier an upstream reference sees a disturbance than an error mic.
 * outputDelayUs is command-to-error-mic delay for the calibrated stereo output path.
 * Aperiodic broadband cancellation needs positive preview margin. Periodic prediction may remain
 * useful with negative margin if route/reference timing is repeatable because future phase can be extrapolated.
 */
public final class PredictiveTimingBudget {
    private PredictiveTimingBudget(){}

    public record Result(long previewMarginUs,double timingConfidence,boolean broadbandFeedforwardEligible,boolean periodicPredictionEligible){}

    public static Result evaluate(long referenceLeadUs,long referenceJitterUs,double referenceConfidence,
                                  long outputDelayUs,long outputJitterUs,double outputConfidence){
        long margin=referenceLeadUs-outputDelayUs;
        long combinedJitter=Math.max(0,referenceJitterUs)+Math.max(0,outputJitterUs);
        double conf=Math.max(0.0,Math.min(1.0,Math.min(referenceConfidence,outputConfidence)));
        // Reserve 2x measured p95 timing variation before enabling stochastic feed-forward cancellation.
        boolean broadband=conf>=0.70&&margin>2L*combinedJitter;
        // Periodic prediction can phase-advance through deterministic delay, but still needs repeatable timing.
        boolean periodic=conf>=0.60&&combinedJitter<=5000L;
        return new Result(margin,conf,broadband,periodic);
    }

    /** Approximate phase uncertainty caused by timing jitter at a particular frequency. */
    public static double jitterPhaseDegrees(long jitterUs,double frequencyHz){
        if(jitterUs<=0||frequencyHz<=0)return 0.0;
        return 360.0*frequencyHz*(jitterUs/1_000_000.0);
    }
}
