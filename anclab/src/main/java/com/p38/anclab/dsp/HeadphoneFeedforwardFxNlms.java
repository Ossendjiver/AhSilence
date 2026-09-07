package com.p38.anclab.dsp;

import java.util.Arrays;

/**
 * Headphone-mode 128-tap feed-forward FxNLMS using the phone microphone as a
 * REFERENCE only. There is no in-ear error microphone during normal use.
 *
 * The adaptive error is therefore a VIRTUAL/MODELLED error:
 *   e_hat[n] = d_hat[n] + S_hat(z)y[n]
 * where d_hat is the phone-mic reference used as an approximation of the
 * disturbance at the ear, and S_hat is the stored calibrated headphone path.
 *
 * This is intentionally not presented as a measured residual. The graph/UI
 * must label predicted cancellation and predicted output as modelled values.
 */
public final class HeadphoneFeedforwardFxNlms {
    public static final int CONTROLLER_TAPS = 128;

    private final float[] w = new float[CONTROLLER_TAPS];
    private final float[] xHist = new float[CONTROLLER_TAPS];
    private final float[] xfHist = new float[CONTROLLER_TAPS];
    private final float[] secondary;
    private final float[] outputHist;
    private final float[] referencePathHist;
    private final int delaySamples;

    private int xPos = 0;
    private int xfPos = 0;
    private int yPos = 0;
    private int pathRefPos = 0;

    private float mu = 0.035f;
    private float leakage = 0.00002f;
    private float outputCeiling;
    private float dcX1 = 0f, dcY1 = 0f;
    private float inRms = 0f, outRms = 0f;

    private volatile float lastReference = 0f;
    private volatile float lastDrive = 0f;
    private volatile float lastPredictedCancellation = 0f;
    private volatile float lastPredictedResidual = 0f;

    public HeadphoneFeedforwardFxNlms(float[] secondaryPath, int delaySamples, float ceiling) {
        secondary = secondaryPath == null || secondaryPath.length == 0
                ? new float[]{1f}
                : Arrays.copyOf(secondaryPath, secondaryPath.length);
        this.delaySamples = Math.max(0, delaySamples);
        int history = Math.max(512, this.delaySamples + secondary.length + 16);
        outputHist = new float[history];
        referencePathHist = new float[history];
        outputCeiling = Math.max(0.02f, Math.min(0.5f, ceiling));
    }

    public void setAdaptationRate(float v) {
        mu = Math.max(0f, Math.min(0.20f, v));
    }

    public float process(float referenceMic) {
        // DC blocker only; retain broadband content above DC.
        float x = referenceMic - dcX1 + 0.995f * dcY1;
        dcX1 = referenceMic;
        dcY1 = x;

        xHist[xPos] = x;
        float y = clamp(dotCircular(w, xHist, xPos), outputCeiling);
        outputHist[yPos] = y;

        // Standard filtered-X reference, using the stored S_hat including bulk delay.
        referencePathHist[pathRefPos] = x;
        float xf = convolveDelayed(referencePathHist, pathRefPos);
        xfHist[xfPos] = xf;

        // Modelled cancellation arriving at the ear/mic after the secondary path.
        float predictedCancellation = convolveDelayed(outputHist, yPos);
        float predictedResidual = x + predictedCancellation;

        float norm = 1e-5f;
        for (float v : xfHist) norm += v * v;
        float step = mu * predictedResidual / norm;
        for (int k = 0; k < CONTROLLER_TAPS; k++) {
            int idx = xfPos - k;
            if (idx < 0) idx += CONTROLLER_TAPS;
            w[k] = (1f - leakage) * w[k] - step * xfHist[idx];
        }

        lastReference = x;
        lastDrive = y;
        lastPredictedCancellation = predictedCancellation;
        lastPredictedResidual = predictedResidual;

        if (++xPos == CONTROLLER_TAPS) xPos = 0;
        if (++xfPos == CONTROLLER_TAPS) xfPos = 0;
        if (++yPos == outputHist.length) yPos = 0;
        if (++pathRefPos == referencePathHist.length) pathRefPos = 0;

        inRms = 0.995f * inRms + 0.005f * x * x;
        outRms = 0.995f * outRms + 0.005f * y * y;
        return y;
    }

    public float inputRms() { return (float)Math.sqrt(Math.max(0f, inRms)); }
    public float outputRms() { return (float)Math.sqrt(Math.max(0f, outRms)); }
    public float diagnosticReference() { return lastReference; }
    public float diagnosticDrive() { return lastDrive; }
    public float diagnosticPredictedCancellation() { return lastPredictedCancellation; }
    public float diagnosticPredictedResidual() { return lastPredictedResidual; }

    private float convolveDelayed(float[] history, int newestPosition) {
        float sum = 0f;
        int p = newestPosition - delaySamples;
        while (p < 0) p += history.length;
        for (float h : secondary) {
            sum += h * history[p];
            if (--p < 0) p = history.length - 1;
        }
        return sum;
    }

    private static float dotCircular(float[] c, float[] h, int newest) {
        float sum = 0f;
        int p = newest;
        for (float v : c) {
            sum += v * h[p];
            if (--p < 0) p = h.length - 1;
        }
        return sum;
    }

    private static float clamp(float v, float ceiling) {
        return Math.max(-ceiling, Math.min(ceiling, v));
    }
}
