package com.p38.anclab.dsp;

import java.util.Arrays;

/**
 * Broadband feedback filtered-x normalized LMS controller for the Headphones profile.
 *
 * Error microphone model:
 *   e[n] = d[n] + S(z)y[n]
 *
 * A feedback reference is reconstructed with the calibrated secondary-path model:
 *   d_hat[n] = e[n] - S_hat(z)y[n]
 *
 * The adaptive controller generates:
 *   y[n] = W(z)d_hat[n]
 *
 * and W is updated from the reference filtered through the same measured secondary
 * path, including both the calibrated bulk delay and its FIR response.
 */
public final class FeedbackFxNlms {
    public static final int CONTROLLER_TAPS = 128;

    private final int taps;
    private final float[] w;
    private final float[] xHist;
    private final float[] xfHist;
    private final float[] s;

    // Circular histories long enough to represent the calibrated bulk delay plus FIR.
    private final float[] yDelay;
    private final float[] refDelay;

    private int xPos = 0;
    private int xfPos = 0;
    private int yPos = 0;
    private int refPos = 0;

    private final int delaySamples;
    private float mu = 0.06f;
    private float leakage = 0.00002f;
    private float outputCeiling = 0.22f;

    private float dcX1 = 0f;
    private float dcY1 = 0f;
    private float inRms = 0f;
    private float outRms = 0f;

    public FeedbackFxNlms(float[] secondaryPath, int delaySamples, int controllerTaps, float ceiling) {
        // Headphone broadband mode is intentionally fixed to a 128-tap adaptive controller.
        taps = CONTROLLER_TAPS;
        w = new float[taps];
        xHist = new float[taps];
        xfHist = new float[taps];

        if (secondaryPath == null || secondaryPath.length == 0) {
            s = new float[]{1f};
        } else {
            s = Arrays.copyOf(secondaryPath, secondaryPath.length);
        }

        this.delaySamples = Math.max(0, delaySamples);
        int pathHistoryLength = Math.max(512, this.delaySamples + s.length + 16);
        yDelay = new float[pathHistoryLength];
        refDelay = new float[pathHistoryLength];

        outputCeiling = Math.max(0.02f, Math.min(0.5f, ceiling));
    }

    public void setAdaptationRate(float v) {
        mu = Math.max(0f, Math.min(0.25f, v));
    }

    public void reset() {
        Arrays.fill(w, 0f);
        Arrays.fill(xHist, 0f);
        Arrays.fill(xfHist, 0f);
        Arrays.fill(yDelay, 0f);
        Arrays.fill(refDelay, 0f);
        xPos = xfPos = yPos = refPos = 0;
        dcX1 = dcY1 = inRms = outRms = 0f;
    }

    public float process(float error) {
        // Remove DC before the adaptive loop. The controller otherwise remains broadband.
        float hp = error - dcX1 + 0.995f * dcY1;
        dcX1 = error;
        dcY1 = hp;

        // Feedback ANC reference reconstruction: subtract the predicted anti-noise return.
        float reference = hp - convolveDelayedOutput();

        xHist[xPos] = reference;
        float y = softLimit(dotCircular(w, xHist, xPos), outputCeiling);
        yDelay[yPos] = y;

        // Filtered-X reference MUST contain the same calibrated bulk delay + FIR as S_hat.
        refDelay[refPos] = reference;
        float xf = convolveDelayedReference();
        xfHist[xfPos] = xf;

        float norm = 1e-5f;
        for (float v : xfHist) norm += v * v;

        // e[n] is hp here; sign follows e = d + S*y.
        float step = mu * hp / norm;
        for (int k = 0; k < taps; k++) {
            int idx = xfPos - k;
            if (idx < 0) idx += taps;
            w[k] = (1f - leakage) * w[k] - step * xfHist[idx];
        }

        if (++xPos == taps) xPos = 0;
        if (++xfPos == taps) xfPos = 0;
        if (++yPos == yDelay.length) yPos = 0;
        if (++refPos == refDelay.length) refPos = 0;

        inRms = 0.995f * inRms + 0.005f * hp * hp;
        outRms = 0.995f * outRms + 0.005f * y * y;
        return y;
    }

    public float inputRms() {
        return (float) Math.sqrt(Math.max(0f, inRms));
    }

    public float outputRms() {
        return (float) Math.sqrt(Math.max(0f, outRms));
    }

    private float dotCircular(float[] c, float[] h, int newest) {
        float sum = 0f;
        int p = newest;
        for (float v : c) {
            sum += v * h[p];
            if (--p < 0) p = h.length - 1;
        }
        return sum;
    }

    /** S_hat(z) applied to generated output, including measured bulk route delay. */
    private float convolveDelayedOutput() {
        float sum = 0f;
        int p = yPos - delaySamples;
        while (p < 0) p += yDelay.length;
        for (float v : s) {
            sum += v * yDelay[p];
            if (--p < 0) p = yDelay.length - 1;
        }
        return sum;
    }

    /** S_hat(z) applied to the adaptive reference, including the same bulk delay. */
    private float convolveDelayedReference() {
        float sum = 0f;
        int p = refPos - delaySamples;
        while (p < 0) p += refDelay.length;
        for (float v : s) {
            sum += v * refDelay[p];
            if (--p < 0) p = refDelay.length - 1;
        }
        return sum;
    }

    private float softLimit(float v, float ceiling) {
        return Math.max(-ceiling, Math.min(ceiling, v));
    }
}
