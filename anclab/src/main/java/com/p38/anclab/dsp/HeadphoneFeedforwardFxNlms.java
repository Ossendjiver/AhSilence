package com.p38.anclab.dsp;

import java.util.Arrays;

/**
 * Predictive 128-tap headphone FxNLMS controller for a phone-mounted reference mic.
 *
 * Normal IEM use has no in-ear error microphone, so the phone microphone is treated
 * strictly as a reference. The calibrated bulk output delay becomes the main prediction
 * horizon. A direct-horizon adaptive linear predictor learns x[n] from x[n-H...]
 * and is then applied to the current reference history to estimate the future disturbance.
 *
 * The active headphone ANC band is deliberately limited to 15-600 Hz. The reference is
 * high-passed at 15 Hz and fourth-order low-passed at 600 Hz before it can train or drive
 * either adaptive stage. The final cancellation drive and the filtered-X model each pass
 * through their own matching fourth-order 600 Hz safety low-pass. This keeps phone/USB/ADC
 * electrical tones and controller-generated HF energy out of the headphone output path.
 */
public final class HeadphoneFeedforwardFxNlms {
    public static final int CONTROLLER_TAPS = 128;
    public static final int PREDICTOR_TAPS = 128;

    private static final int SAMPLE_RATE = 48000;
    private static final int PREDICTOR_ADAPT_DECIMATION = 4;
    private static final float EPS = 1e-8f;
    private static final float INVERSE_REGULARISATION = 4.0f;
    private static final float CONTROLLER_WEIGHT_LIMIT = 64.0f;

    private final int horizonSamples;
    private final int inverseDelaySamples;
    private final int outputFilterDelaySamples;
    private final float[] secondary;

    // Keep the three filters independent: they represent different causal signal paths.
    private final HeadphoneBandLimiter referenceBand = new HeadphoneBandLimiter(SAMPLE_RATE, true);
    private final HeadphoneBandLimiter outputLowPass = new HeadphoneBandLimiter(SAMPLE_RATE, false);
    private final HeadphoneBandLimiter filteredXPathLowPass = new HeadphoneBandLimiter(SAMPLE_RATE, false);

    // Direct-horizon predictor.
    private final float[] predictorWeights = new float[PREDICTOR_TAPS];
    private final float[] referenceHistory;
    private int referencePos = 0;
    private long samplesSeen = 0;
    private int predictorAdaptCounter = 0;
    private float predictorMu = 0.08f;
    private float predictorLeakage = 0.000002f;
    private float signalPower = 1e-8f;
    private float predictorErrorPower = 1e-8f;
    private float confidence = 0f;
    private float confidenceSmooth = 0f;

    // 128-tap cancellation controller.
    private final float[] controllerWeights = new float[CONTROLLER_TAPS];
    private final float[] predictedHistory = new float[CONTROLLER_TAPS];
    private final float[] filteredPredictedHistory = new float[CONTROLLER_TAPS];
    private int predictedPos = 0;
    private int filteredPos = 0;

    // FIR-only secondary-path histories. Bulk latency is handled by prediction horizon.
    private final float[] driveHistory;
    private final float[] predictorPathHistory;
    private int drivePos = 0;
    private int pathPos = 0;

    private float controllerMu = 0.035f;
    private float controllerLeakage = 0.00000001f;
    private float outputCeiling;

    private float inRms = 0f;
    private float outRms = 0f;

    private volatile float lastRawReference = 0f;
    private volatile float lastReference = 0f;
    private volatile float lastPredictedFuture = 0f;
    private volatile float lastDrive = 0f;
    private volatile float lastPredictedCancellation = 0f;
    private volatile float lastPredictedResidual = 0f;

    public HeadphoneFeedforwardFxNlms(float[] secondaryPath, int bulkDelaySamples, float ceiling) {
        secondary = secondaryPath == null || secondaryPath.length == 0
                ? new float[]{1f}
                : Arrays.copyOf(secondaryPath, secondaryPath.length);

        // A reversed-path matched inverse produces its main cancellation lobe after
        // secondary.length - 1 samples. The final 600 Hz output filter adds another small
        // causal delay; forecast through both so filtering does not silently make us late.
        inverseDelaySamples = Math.max(0, secondary.length - 1);
        outputFilterDelaySamples = HeadphoneBandLimiter.approximateOutputDelaySamples(SAMPLE_RATE);
        horizonSamples = Math.max(1, bulkDelaySamples + inverseDelaySamples + outputFilterDelaySamples);

        referenceHistory = new float[horizonSamples + PREDICTOR_TAPS + 32];
        int pathHistory = Math.max(512, secondary.length + 32);
        driveHistory = new float[pathHistory];
        predictorPathHistory = new float[pathHistory];
        outputCeiling = clamp(Math.abs(ceiling), 0.02f, 0.5f);

        seedControllerFromSecondaryPath();
    }

    /**
     * Conservative regularised matched inverse:
     *     W[k] = -reverse(S)[k] / ((1 + lambda) * ||S||^2)
     */
    private void seedControllerFromSecondaryPath() {
        float energy = 0f;
        for (float h : secondary) energy += h * h;
        if (energy < 1e-12f) return;

        float denominator = energy * (1f + INVERSE_REGULARISATION) + 1e-12f;
        Arrays.fill(controllerWeights, 0f);
        int n = Math.min(CONTROLLER_TAPS, secondary.length);
        for (int k = 0; k < n; k++) {
            int hi = secondary.length - 1 - k;
            controllerWeights[k] = clamp(-secondary[hi] / denominator,
                    -CONTROLLER_WEIGHT_LIMIT, CONTROLLER_WEIGHT_LIMIT);
        }
    }

    public void setAdaptationRate(float v) {
        controllerMu = clamp(v, 0f, 0.15f);
    }

    public void setPredictorAdaptationRate(float v) {
        predictorMu = clamp(v, 0f, 0.25f);
    }

    public float process(float referenceMic) {
        lastRawReference = referenceMic;

        // Critical safety boundary: only 15-600 Hz reaches prediction or adaptation.
        float x = referenceBand.process(referenceMic);
        referenceHistory[referencePos] = x;
        samplesSeen++;

        if (samplesSeen > horizonSamples + PREDICTOR_TAPS) {
            if (++predictorAdaptCounter >= PREDICTOR_ADAPT_DECIMATION) {
                predictorAdaptCounter = 0;
                adaptPredictor(x);
            }
        }

        float predictedFuture = samplesSeen > horizonSamples + PREDICTOR_TAPS
                ? dotCircular(predictorWeights, referenceHistory, referencePos)
                : 0f;

        float rms = (float)Math.sqrt(Math.max(signalPower, 1e-8f));
        float predictionLimit = Math.max(0.004f, Math.min(0.35f, 3.0f * rms));
        predictedFuture = clamp(predictedFuture, -predictionLimit, predictionLimit);

        confidenceSmooth = 0.998f * confidenceSmooth + 0.002f * confidence;
        float outputGate = smoothstep(0.05f, 0.45f, confidenceSmooth);

        predictedHistory[predictedPos] = predictedFuture;
        float rawDrive = dotCircular(controllerWeights, predictedHistory, predictedPos) * outputGate;

        // Final independent HF guard. Clamp after filtering too in case of filter transient.
        float drive = outputLowPass.process(clamp(rawDrive, -outputCeiling, outputCeiling));
        drive = clamp(drive, -outputCeiling, outputCeiling);
        driveHistory[drivePos] = drive;

        float predictedCancellation = convolveSecondary(driveHistory, drivePos);
        float predictedResidual = predictedFuture + predictedCancellation;

        // Filtered-X sees the same 600 Hz output-filter dynamics as the real drive path.
        float predictedThroughOutputFilter = filteredXPathLowPass.process(predictedFuture);
        predictorPathHistory[pathPos] = predictedThroughOutputFilter;
        float xf = convolveSecondary(predictorPathHistory, pathPos);
        filteredPredictedHistory[filteredPos] = xf;

        float adaptGate = smoothstep(0.02f, 0.30f, confidenceSmooth);
        if (adaptGate > 0f) {
            float norm = 1e-5f;
            for (float v : filteredPredictedHistory) norm += v * v;
            float step = controllerMu * adaptGate * predictedResidual / norm;
            for (int k = 0; k < CONTROLLER_TAPS; k++) {
                int idx = filteredPos - k;
                if (idx < 0) idx += CONTROLLER_TAPS;
                controllerWeights[k] = clamp(
                        (1f - controllerLeakage) * controllerWeights[k]
                                - step * filteredPredictedHistory[idx],
                        -CONTROLLER_WEIGHT_LIMIT,
                        CONTROLLER_WEIGHT_LIMIT);
            }
        }

        lastReference = x;
        lastPredictedFuture = predictedFuture;
        lastDrive = drive;
        lastPredictedCancellation = predictedCancellation;
        lastPredictedResidual = predictedResidual;

        if (++referencePos == referenceHistory.length) referencePos = 0;
        if (++predictedPos == CONTROLLER_TAPS) predictedPos = 0;
        if (++filteredPos == CONTROLLER_TAPS) filteredPos = 0;
        if (++drivePos == driveHistory.length) drivePos = 0;
        if (++pathPos == predictorPathHistory.length) pathPos = 0;

        inRms = 0.995f * inRms + 0.005f * x * x;
        outRms = 0.995f * outRms + 0.005f * drive * drive;
        return drive;
    }

    private void adaptPredictor(float currentTarget) {
        int oldNewest = referencePos - horizonSamples;
        while (oldNewest < 0) oldNewest += referenceHistory.length;

        float prediction = dotCircular(predictorWeights, referenceHistory, oldNewest);
        float error = currentTarget - prediction;

        float norm = 1e-6f;
        int p = oldNewest;
        for (int k = 0; k < PREDICTOR_TAPS; k++) {
            float v = referenceHistory[p];
            norm += v * v;
            if (--p < 0) p = referenceHistory.length - 1;
        }

        float step = predictorMu * error / norm;
        p = oldNewest;
        for (int k = 0; k < PREDICTOR_TAPS; k++) {
            predictorWeights[k] = (1f - predictorLeakage) * predictorWeights[k]
                    + step * referenceHistory[p];
            if (--p < 0) p = referenceHistory.length - 1;
        }

        signalPower = 0.9995f * signalPower + 0.0005f * currentTarget * currentTarget;
        predictorErrorPower = 0.9995f * predictorErrorPower + 0.0005f * error * error;
        confidence = clamp(1f - predictorErrorPower / (signalPower + EPS), 0f, 1f);
    }

    public float inputRms() { return (float)Math.sqrt(Math.max(0f, inRms)); }
    public float outputRms() { return (float)Math.sqrt(Math.max(0f, outRms)); }
    public float predictorConfidence() { return confidenceSmooth; }
    public int predictionHorizonSamples() { return horizonSamples; }
    public int inverseDelaySamples() { return inverseDelaySamples; }
    public int outputFilterDelaySamples() { return outputFilterDelaySamples; }

    public float diagnosticRawReference() { return lastRawReference; }
    public float diagnosticReference() { return lastReference; }
    public float diagnosticPredictedFuture() { return lastPredictedFuture; }
    public float diagnosticDrive() { return lastDrive; }
    public float diagnosticPredictedCancellation() { return lastPredictedCancellation; }
    public float diagnosticPredictedResidual() { return lastPredictedResidual; }

    private float convolveSecondary(float[] history, int newest) {
        float sum = 0f;
        int p = newest;
        for (float h : secondary) {
            sum += h * history[p];
            if (--p < 0) p = history.length - 1;
        }
        return sum;
    }

    private static float dotCircular(float[] coefficients, float[] history, int newest) {
        float sum = 0f;
        int p = newest;
        for (float c : coefficients) {
            sum += c * history[p];
            if (--p < 0) p = history.length - 1;
        }
        return sum;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / Math.max(1e-6f, edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
