package com.p38.anclab.dsp;

import java.util.Locale;

/** Conservative, bounded calibration and tracking for one sinusoidal cancellation lane. */
public final class AutoController {
    public record Output(double frequencyHz, double gain, double phaseRadians, String status) {
        public Complex coefficient() { return Complex.polar(gain, phaseRadians); }
    }

    private enum Stage {
        IDLE, LISTENING, BASELINE, VERIFY_RECIPE, PROBE_POSITIVE, PROBE_NEGATIVE,
        VERIFY_HALF, VERIFY_FULL, RUNNING, VERIFY_FINE, SEEK_FIRST, SEEK_SECOND,
        SEEK_RETURN, FOLLOW_VERIFY, AUDIT_OFF, AUDIT_ON
    }

    private static final long LISTEN_MS = 1500;
    private static final long SETTLE_MS = 650;
    private static final long ADAPT_INTERVAL_MS = 350;
    private static final double SEEK_STEP_HZ = 0.08;
    private static final double FOLLOW_DEADBAND_HZ = 0.04;
    private static final double CALIBRATION_RETUNE_HYSTERESIS_HZ = 0.55;
    private static final double RUNNING_VERIFY_HYSTERESIS_HZ = 0.20;
    private static final double ROOM_MIN_VERIFIED_REDUCTION_DB = 1.0;
    private static final long ROOM_AUDIT_BASE_INTERVAL_MS = 60000L;
    private static final long ROOM_AUDIT_SPREAD_MS = 15000L;
    private static final double ROOM_SOFT_AUDIT_SCALE = 0.35;
    private static final double ROOM_SOFT_AUDIT_MIN_DELTA_DB = 0.35;
    private static final double ROOM_VIRTUAL_VERIFY_MIN_DB = 0.45;
    private static final int ROOM_VIRTUAL_VERIFY_FAILURE_WINDOWS = 12;
    private static final double ROOM_VIRTUAL_VERIFY_SMOOTHING = 0.18;

    private Stage stage = Stage.IDLE;
    private long stageStartedMs;
    private double maximumGain = 0.02;
    private double frequencyHz = 34.5;
    private Complex baseline = Complex.ZERO;
    private Complex secondaryPath = Complex.ZERO;
    private Complex command = Complex.ZERO;
    private Complex previousCommand = Complex.ZERO;
    private Complex recipeCommand = Complex.ZERO;
    private Complex learnedSecondaryPath = Complex.ZERO;
    private boolean usingLearnedSecondaryPath;
    private Complex positiveProbeCommand = Complex.ZERO;
    private Complex positiveProbeResidual = Complex.ZERO;
    private double previousResidual = Double.POSITIVE_INFINITY;
    private double baselineResidual = Double.POSITIVE_INFINITY;
    private String status = "Ready";
    private boolean fixedTarget;
    private boolean directErrorLearning;
    private String label = "Dominant";
    private double currentImprovementDb = Double.NaN;
    private int rejectedAdaptations;
    private double seekCentreFrequency;
    private double seekCentreResidual;
    private double seekDirection;
    private double seekBestFrequency;
    private double seekBestResidual;
    private Complex auditCommand = Complex.ZERO;
    private Complex auditOffResidual = Complex.ZERO;
    private long lastAuditCompletedMs;
    private boolean activeVerificationPassed;
    private boolean activeVerificationFailed;
    private double auditReducedScale;
    private double auditRequiredReductionDb = ROOM_MIN_VERIFIED_REDUCTION_DB;
    private double smoothedVirtualImprovementDb = Double.NaN;
    private int virtualVerificationFailures;

    public synchronized void start(long nowMs, double maximumGain) {
        configure(nowMs, maximumGain, 34.5, false, "Dominant");
        stage = Stage.LISTENING;
        status = "Listening with output muted…";
    }

    public synchronized void startFixed(long nowMs, double maximumGain, double frequencyHz, String label) {
        configure(nowMs, maximumGain, frequencyHz, true, label);
        stage = Stage.BASELINE;
        status = label + ": measuring baseline…";
    }

    public synchronized void startTracking(long nowMs, double maximumGain, double frequencyHz,
                                           String label, boolean fixedByTelemetry) {
        startTracking(nowMs, maximumGain, frequencyHz, label, fixedByTelemetry, 0, 0);
    }

    public synchronized void startTracking(long nowMs, double maximumGain, double frequencyHz,
                                           String label, boolean fixedByTelemetry,
                                           double recipeGain, double recipePhaseDegrees) {
        configure(nowMs, maximumGain, frequencyHz, fixedByTelemetry, label);
        if (Double.isFinite(recipeGain) && recipeGain > 0) {
            recipeCommand = Complex.polar(Math.min(recipeGain, this.maximumGain),
                    Math.toRadians(recipePhaseDegrees));
        }
        stage = Stage.BASELINE;
        status = recipeCommand.magnitude() > 0
                ? label + ": checking learned recipe…" : label + ": measuring baseline…";
    }

    /**
     * Reuses a verified speaker-to-microphone path, never a prior anti-noise phase. The current
     * disturbance phase is measured first and the calculated command is verified at half strength.
     */
    public synchronized void startTrackingWithSecondaryPath(long nowMs, double maximumGain,
                                                              double frequencyHz, String label,
                                                              boolean fixedByTelemetry,
                                                              Complex storedSecondaryPath) {
        configure(nowMs, maximumGain, frequencyHz, fixedByTelemetry, label);
        if (storedSecondaryPath != null && storedSecondaryPath.magnitude() >= 1.0e-5)
            learnedSecondaryPath = storedSecondaryPath;
        stage = Stage.BASELINE;
        status = learnedSecondaryPath.magnitude() > 0
                ? label + ": measuring baseline for learned path…" : label + ": measuring baseline…";
    }

    private void configure(long nowMs, double maximumGain, double frequencyHz,
                           boolean fixedTarget, String label) {
        this.maximumGain = clamp(maximumGain, 0.0001, 0.15);
        this.frequencyHz = clamp(frequencyHz, 8.0, 600.0);
        this.fixedTarget = fixedTarget;
        this.label = label;
        stageStartedMs = nowMs;
        command = Complex.ZERO;
        previousCommand = Complex.ZERO;
        baseline = Complex.ZERO;
        secondaryPath = Complex.ZERO;
        recipeCommand = Complex.ZERO;
        learnedSecondaryPath = Complex.ZERO;
        usingLearnedSecondaryPath = false;
        positiveProbeCommand = Complex.ZERO;
        positiveProbeResidual = Complex.ZERO;
        previousResidual = Double.POSITIVE_INFINITY;
        baselineResidual = Double.POSITIVE_INFINITY;
        currentImprovementDb = Double.NaN;
        rejectedAdaptations = 0;
        auditCommand = Complex.ZERO;
        auditOffResidual = Complex.ZERO;
        lastAuditCompletedMs = nowMs;
        activeVerificationPassed = false;
        activeVerificationFailed = false;
        auditReducedScale = 0.0;
        auditRequiredReductionDb = ROOM_MIN_VERIFIED_REDUCTION_DB;
        smoothedVirtualImprovementDb = Double.NaN;
        virtualVerificationFailures = 0;
    }

    public synchronized void stop() {
        stage = Stage.IDLE;
        command = Complex.ZERO;
        status = label + ": stopped";
    }

    public synchronized void setDirectErrorLearning(boolean enabled) { directErrorLearning = enabled; }

    private long settleMs() { return directErrorLearning ? 420L : SETTLE_MS; }
    private long adaptIntervalMs() { return directErrorLearning ? 220L : ADAPT_INTERVAL_MS; }

    public synchronized void setMaximumGain(double maximumGain) {
        this.maximumGain = clamp(maximumGain, 0.0001, 0.15);
        command = command.clampMagnitude(this.maximumGain);
    }

    /** Refresh the route-calibrated complex path at the controller's current frequency. */
    public synchronized void refreshSecondaryPath(Complex refreshedPath) {
        if (!directErrorLearning || refreshedPath == null || refreshedPath.magnitude() < 1.0e-5) return;
        if (secondaryPath.magnitude() >= 1.0e-5) {
            if (command.magnitude() > 0 && (stage == Stage.VERIFY_HALF || stage == Stage.VERIFY_FULL
                    || stage == Stage.RUNNING || stage == Stage.VERIFY_FINE || stage == Stage.FOLLOW_VERIFY
                    || stage == Stage.AUDIT_OFF || stage == Stage.AUDIT_ON)) {
                Complex predictedSpeakerContribution = secondaryPath.multiply(command);
                command = predictedSpeakerContribution.divide(refreshedPath).clampMagnitude(maximumGain);
            }
            secondaryPath = refreshedPath;
        } else if (stage == Stage.BASELINE || learnedSecondaryPath.magnitude() >= 1.0e-5) {
            learnedSecondaryPath = refreshedPath;
        }
    }

    /**
     * Follow a measured/predicted tone without continuously invalidating an in-flight path probe.
     * During BASELINE/PROBE/VERIFY calibration the controller holds its acoustic centre through small
     * tracker jitter and only restarts once the requested move is large enough to represent a real
     * retune. Once RUNNING, small motion is followed continuously; only a larger jump gets a settled
     * verification state.
     */
    public synchronized void followFrequency(double requestedHz, long nowMs) {
        requestedHz = clamp(requestedHz, 8.0, 600.0);
        double delta = requestedHz - frequencyHz;
        double distance = Math.abs(delta);
        if (distance < FOLLOW_DEADBAND_HZ) return;

        if (stage == Stage.RUNNING) {
            frequencyHz = requestedHz;
            if (distance >= RUNNING_VERIFY_HYSTERESIS_HZ) {
                stage = Stage.FOLLOW_VERIFY;
                stageStartedMs = nowMs;
                status = String.format(Locale.US, "%s: following %.2f Hz…", label, frequencyHz);
            }
            return;
        }

        if (stage == Stage.VERIFY_FINE || stage == Stage.FOLLOW_VERIFY) {
            frequencyHz = requestedHz;
            if (distance >= RUNNING_VERIFY_HYSTERESIS_HZ) {
                stage = Stage.FOLLOW_VERIFY;
                stageStartedMs = nowMs;
                status = String.format(Locale.US, "%s: following %.2f Hz…", label, frequencyHz);
            }
            return;
        }

        if (stage == Stage.LISTENING || stage == Stage.IDLE) {
            frequencyHz = requestedHz;
            return;
        }

        if (distance < CALIBRATION_RETUNE_HYSTERESIS_HZ) {
            // Hold the calibrated oscillator/analysis centre until this probe finishes. The caller
            // must analyze the controller's output().frequencyHz(), not the raw tracker estimate.
            return;
        }

        if (secondaryPath.magnitude() >= 1.0e-5) learnedSecondaryPath = secondaryPath;
        frequencyHz = requestedHz;
        stage = Stage.BASELINE;
        stageStartedMs = nowMs;
        command = Complex.ZERO;
        status = label + ": model moved materially; refreshing baseline…";
    }

    public synchronized Output update(SpectrumSnapshot snapshot, long nowMs) {
        switch (stage) {
            case IDLE -> command = Complex.ZERO;
            case LISTENING -> updateListening(snapshot, nowMs);
            case BASELINE -> updateBaseline(snapshot, nowMs);
            case VERIFY_RECIPE -> updateRecipe(snapshot, nowMs);
            case PROBE_POSITIVE -> updatePositiveProbe(snapshot, nowMs);
            case PROBE_NEGATIVE -> updateNegativeProbe(snapshot, nowMs);
            case VERIFY_HALF -> updateHalf(snapshot, nowMs);
            case VERIFY_FULL -> updateFull(snapshot, nowMs);
            case RUNNING -> updateRunning(snapshot, nowMs);
            case VERIFY_FINE -> updateFine(snapshot, nowMs);
            case SEEK_FIRST -> updateSeekFirst(snapshot, nowMs);
            case SEEK_SECOND -> updateSeekSecond(snapshot, nowMs);
            case SEEK_RETURN -> updateSeekReturn(snapshot, nowMs);
            case FOLLOW_VERIFY -> updateFollow(snapshot, nowMs);
            case AUDIT_OFF -> updateAuditOff(snapshot, nowMs);
            case AUDIT_ON -> updateAuditOn(snapshot, nowMs);
        }
        return output();
    }

    private void updateListening(SpectrumSnapshot snapshot, long nowMs) {
        command = Complex.ZERO;
        if (elapsed(nowMs) < LISTEN_MS || snapshot.secondsAvailable() < 1.2) return;
        if (snapshot.peakDbFs() < -72.0 || snapshot.contrastDb() < 1.5) {
            stageStartedMs = nowMs;
            status = "No clear 25–45 Hz tone yet; still listening…";
            return;
        }
        frequencyHz = snapshot.peakFrequencyHz();
        stage = Stage.BASELINE;
        stageStartedMs = nowMs;
        status = String.format(Locale.US, "%s: locked %.2f Hz; baseline…", label, frequencyHz);
    }

    private void updateBaseline(SpectrumSnapshot snapshot, long nowMs) {
        command = Complex.ZERO;
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        baseline = snapshot.targetComplex();
        baselineResidual = baseline.magnitude();
        if (learnedSecondaryPath.magnitude() >= 1.0e-5) {
            secondaryPath = learnedSecondaryPath;
            learnedSecondaryPath = Complex.ZERO;
            usingLearnedSecondaryPath = true;
            previousCommand = Complex.ZERO;
            previousResidual = baselineResidual;
            command = baseline.negate().divide(secondaryPath)
                    .clampMagnitude(maximumGain).multiply(0.5);
            stage = Stage.VERIFY_HALF;
            stageStartedMs = nowMs;
            status = label + ": validating learned path at half strength…";
            return;
        }
        if (recipeCommand.magnitude() > 0) {
            command = recipeCommand;
            stage = Stage.VERIFY_RECIPE;
            stageStartedMs = nowMs;
            status = label + ": validating learned phase and level…";
            return;
        }
        beginProbe(nowMs);
    }

    private void beginProbe(long nowMs) {
        if (directErrorLearning) {
            rejectActiveVerification("no trustworthy calibrated secondary path; lane quarantined");
            return;
        }
        double probeGain = Math.min(maximumGain, Math.max(0.0002, maximumGain * 0.5));
        command = Complex.polar(probeGain, 0.0);
        stage = Stage.PROBE_POSITIVE;
        stageStartedMs = nowMs;
        status = label + ": measuring acoustic path (+)…";
    }

    private void updateRecipe(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        double residual = snapshot.targetComplex().magnitude();
        Complex delta = snapshot.targetComplex().subtract(baseline);
        if (residual > baselineResidual * 1.05 || delta.magnitude() < Math.max(1.0e-5, baselineResidual * 0.02)) {
            recipeCommand = Complex.ZERO;
            command = Complex.ZERO;
            beginProbe(nowMs);
            return;
        }
        secondaryPath = delta.divide(command);
        if (secondaryPath.magnitude() < 1.0e-5) {
            recipeCommand = Complex.ZERO;
            command = Complex.ZERO;
            beginProbe(nowMs);
            return;
        }
        previousCommand = command;
        previousResidual = residual;
        command = baseline.negate().divide(secondaryPath).clampMagnitude(maximumGain);
        stage = Stage.VERIFY_FULL;
        stageStartedMs = nowMs;
        status = label + ": refining learned recipe…";
    }

    private void updatePositiveProbe(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        positiveProbeCommand = command;
        positiveProbeResidual = snapshot.targetComplex();
        command = command.negate();
        stage = Stage.PROBE_NEGATIVE;
        stageStartedMs = nowMs;
        status = label + ": measuring acoustic path (−)…";
    }

    private void updateNegativeProbe(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        Complex negativeProbeResidual = snapshot.targetComplex();
        Complex difference = positiveProbeResidual.subtract(negativeProbeResidual);
        secondaryPath = difference.divide(positiveProbeCommand.multiply(2.0));
        Complex balancedDisturbance = positiveProbeResidual.add(negativeProbeResidual).multiply(0.5);
        if (secondaryPath.magnitude() < 1.0e-5
                || difference.magnitude() < Math.max(1.0e-5, baselineResidual * 0.04)) {
            reject("probe was not measurable");
            return;
        }
        baseline = balancedDisturbance;
        baselineResidual = balancedDisturbance.magnitude();
        Complex optimum = baseline.negate().divide(secondaryPath).clampMagnitude(maximumGain);
        command = optimum.multiply(0.5);
        previousCommand = Complex.ZERO;
        previousResidual = baselineResidual;
        stage = Stage.VERIFY_HALF;
        stageStartedMs = nowMs;
        status = label + ": testing half strength…";
    }

    private void updateHalf(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        double residual = snapshot.targetComplex().magnitude();
        if (residual > baselineResidual * 1.03) {
            if (usingLearnedSecondaryPath) {
                usingLearnedSecondaryPath = false;
                if (directErrorLearning) {
                    rejectActiveVerification("calibrated path increased the measured error");
                    return;
                }
                command = Complex.ZERO;
                beginProbe(nowMs);
                return;
            }
            reject("trial became louder");
            return;
        }
        previousCommand = command;
        previousResidual = residual;
        command = baseline.negate().divide(secondaryPath).clampMagnitude(maximumGain);
        stage = Stage.VERIFY_FULL;
        stageStartedMs = nowMs;
        status = label + ": testing calculated level…";
    }

    private void updateFull(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        double residual = snapshot.targetComplex().magnitude();
        if (residual > previousResidual * 1.03) {
            command = previousCommand;
            residual = previousResidual;
        }
        double requiredRatio = directErrorLearning
                ? Math.pow(10.0, -ROOM_MIN_VERIFIED_REDUCTION_DB / 20.0) : 0.99;
        if (residual >= baselineResidual * requiredRatio) {
            if (usingLearnedSecondaryPath) {
                usingLearnedSecondaryPath = false;
                if (directErrorLearning) {
                    rejectActiveVerification("calibrated path did not produce repeatable reduction");
                    return;
                }
                command = Complex.ZERO;
                beginProbe(nowMs);
                return;
            }
            reject("no repeatable reduction");
            return;
        }
        usingLearnedSecondaryPath = false;
        previousResidual = residual;
        previousCommand = command;
        if (directErrorLearning) {
            beginRoomAudit(nowMs, "certifying cancellation against a muted baseline…",
                    0.0, ROOM_MIN_VERIFIED_REDUCTION_DB);
            return;
        }
        stage = Stage.RUNNING;
        stageStartedMs = nowMs;
        status = improvementStatus(residual);
    }

    private void updateRunning(SpectrumSnapshot snapshot, long nowMs) {
        if (directErrorLearning && activeVerificationPassed) {
            if (updateRoomVirtualVerification(snapshot, nowMs)) return;
            if (nowMs - lastAuditCompletedMs >= roomAuditIntervalMs()) {
                beginRoomAudit(nowMs, "gently re-checking full versus reduced ANC…",
                        ROOM_SOFT_AUDIT_SCALE, ROOM_SOFT_AUDIT_MIN_DELTA_DB);
                return;
            }
        }
        if (!fixedTarget && elapsed(nowMs) >= settleMs()) {
            double drift = snapshot.peakFrequencyHz() - frequencyHz;
            if (Math.abs(drift) >= 0.06 && Math.abs(drift) <= 0.60) {
                seekCentreFrequency = frequencyHz;
                seekCentreResidual = snapshot.targetComplex().magnitude();
                seekDirection = Math.signum(drift);
                seekBestFrequency = seekCentreFrequency;
                seekBestResidual = seekCentreResidual;
                frequencyHz = seekCentreFrequency + seekDirection * SEEK_STEP_HZ;
                stage = Stage.SEEK_FIRST;
                stageStartedMs = nowMs;
                status = label + ": testing toward the shifted peak…";
                return;
            }
        }
        if (targetMatches(snapshot) && snapshot.targetComplex().magnitude() <= baselineResidual * 0.08) {
            previousResidual = snapshot.targetComplex().magnitude();
            rejectedAdaptations = 0;
            stageStartedMs = nowMs;
            status = improvementStatus(previousResidual);
            return;
        }
        if (elapsed(nowMs) >= adaptIntervalMs() && targetMatches(snapshot)) {
            Complex error = snapshot.targetComplex();
            double residual = error.magnitude();
            double reference = Double.isFinite(previousResidual) ? previousResidual : baselineResidual;
            double innovation = Math.abs(residual - reference) / Math.max(reference, baselineResidual * 0.05);
            double agileWeight = clamp(innovation * 1.5, 0.0, 1.0);
            double stepSize = directErrorLearning
                    ? 0.07 * (1.0 - agileWeight) + 0.16 * agileWeight
                    : 0.06 * (1.0 - agileWeight) + 0.20 * agileWeight;
            double normalization = secondaryPath.magnitudeSquared() + 1.0e-10;
            double correctionLimit = maximumGain * (directErrorLearning ? 0.12 : 0.18);
            Complex gradient = secondaryPath.conjugate().multiply(error)
                    .multiply(-stepSize / normalization).clampMagnitude(correctionLimit);
            previousCommand = command;
            previousResidual = residual;
            command = command.multiply(0.9995).add(gradient).clampMagnitude(maximumGain);
            stage = Stage.VERIFY_FINE;
            stageStartedMs = nowMs;
            status = label + ": filtered-X adapting phase and level…";
        }
    }

    private void updateFine(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        Complex measuredCommand = command;
        double residual = snapshot.targetComplex().magnitude();
        if (residual > Math.min(baselineResidual * 1.02, previousResidual * 1.25)) {
            command = previousCommand;
            residual = previousResidual;
            rejectedAdaptations++;
            if (rejectedAdaptations >= 3) {
                // The secondary path usually remains valid while road/engine phase changes. Infer
                // the new disturbance from the measured residual and current command, then safely
                // verify a freshly calculated command at half strength before considering a new
                // acoustic path probe.
                baseline = snapshot.targetComplex().subtract(secondaryPath.multiply(measuredCommand));
                baselineResidual = baseline.magnitude();
                previousCommand = command;
                previousResidual = residual;
                command = baseline.negate().divide(secondaryPath)
                        .clampMagnitude(maximumGain).multiply(0.5);
                usingLearnedSecondaryPath = true;
                stage = Stage.VERIFY_HALF;
                stageStartedMs = nowMs;
                rejectedAdaptations = 0;
                status = label + ": disturbance changed; validating fresh phase…";
                return;
            }
        } else {
            previousCommand = command;
            previousResidual = residual;
            rejectedAdaptations = 0;
        }
        stage = Stage.RUNNING;
        stageStartedMs = nowMs;
        status = improvementStatus(residual);
    }

    private void updateSeekFirst(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        double residual = snapshot.targetComplex().magnitude();
        if (residual < seekBestResidual) { seekBestResidual = residual; seekBestFrequency = frequencyHz; }
        frequencyHz = seekCentreFrequency - seekDirection * SEEK_STEP_HZ;
        stage = Stage.SEEK_SECOND;
        stageStartedMs = nowMs;
        status = label + ": checking the other side…";
    }

    private void updateSeekSecond(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        double residual = snapshot.targetComplex().magnitude();
        if (residual < seekBestResidual) { seekBestResidual = residual; seekBestFrequency = frequencyHz; }
        frequencyHz = seekBestFrequency;
        stage = Stage.SEEK_RETURN;
        stageStartedMs = nowMs;
        status = String.format(Locale.US, "%s: returning to best %.2f Hz…", label, frequencyHz);
    }

    private void updateSeekReturn(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        previousResidual = snapshot.targetComplex().magnitude();
        stage = Stage.RUNNING;
        stageStartedMs = nowMs;
        status = improvementStatus(previousResidual);
    }

    private void updateFollow(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        previousResidual = snapshot.targetComplex().magnitude();
        stage = Stage.RUNNING;
        stageStartedMs = nowMs;
        status = String.format(Locale.US, "%s: tracking %.2f Hz", label, frequencyHz);
    }

    private boolean updateRoomVirtualVerification(SpectrumSnapshot snapshot, long nowMs) {
        if (!targetMatches(snapshot) || secondaryPath.magnitude() < 1.0e-5
                || command.magnitude() < 1.0e-7) return false;
        double virtualImprovement = estimatedFullImprovementDb(snapshot.targetComplex());
        if (!Double.isFinite(virtualImprovement)) return false;
        smoothedVirtualImprovementDb = Double.isFinite(smoothedVirtualImprovementDb)
                ? smoothedVirtualImprovementDb * (1.0 - ROOM_VIRTUAL_VERIFY_SMOOTHING)
                    + virtualImprovement * ROOM_VIRTUAL_VERIFY_SMOOTHING
                : virtualImprovement;
        currentImprovementDb = smoothedVirtualImprovementDb;
        if (smoothedVirtualImprovementDb >= ROOM_VIRTUAL_VERIFY_MIN_DB) {
            virtualVerificationFailures = Math.max(0, virtualVerificationFailures - 2);
            return false;
        }
        if (++virtualVerificationFailures < ROOM_VIRTUAL_VERIFY_FAILURE_WINDOWS) return false;
        virtualVerificationFailures = 0;
        beginRoomAudit(nowMs, "predicted benefit weakened; checking at reduced strength…",
                ROOM_SOFT_AUDIT_SCALE, ROOM_SOFT_AUDIT_MIN_DELTA_DB);
        return true;
    }

    private double estimatedFullImprovementDb(Complex residual) {
        Complex estimatedOff = residual.subtract(secondaryPath.multiply(command));
        return 20.0 * Math.log10(Math.max(estimatedOff.magnitude(), 1.0e-9)
                / Math.max(residual.magnitude(), 1.0e-9));
    }

    private void beginRoomAudit(long nowMs, String message,
                                double reducedScale, double requiredReductionDb) {
        auditCommand = command;
        auditReducedScale = clamp(reducedScale, 0.0, 0.95);
        auditRequiredReductionDb = Math.max(0.05, requiredReductionDb);
        command = auditCommand.multiply(auditReducedScale);
        auditOffResidual = Complex.ZERO;
        stage = Stage.AUDIT_OFF;
        stageStartedMs = nowMs;
        status = label + ": " + message;
    }

    private void updateAuditOff(SpectrumSnapshot snapshot, long nowMs) {
        command = auditCommand.multiply(auditReducedScale);
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        auditOffResidual = snapshot.targetComplex();
        baseline = auditOffResidual;
        baselineResidual = auditOffResidual.magnitude();
        if (!Double.isFinite(baselineResidual) || baselineResidual < 1.0e-7
                || auditCommand.magnitude() < 1.0e-7) {
            rejectActiveVerification("muted baseline was not measurable");
            return;
        }
        command = auditCommand.clampMagnitude(maximumGain);
        stage = Stage.AUDIT_ON;
        stageStartedMs = nowMs;
        status = label + ": restoring ANC for measured A/B verification…";
    }

    private void updateAuditOn(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        double onResidual = snapshot.targetComplex().magnitude();
        double measuredDelta = 20.0 * Math.log10(Math.max(baselineResidual, 1.0e-9)
                / Math.max(onResidual, 1.0e-9));
        if (!Double.isFinite(measuredDelta) || measuredDelta < auditRequiredReductionDb) {
            rejectActiveVerification(String.format(Locale.US,
                    "%s A/B check found only %.1f dB benefit",
                    auditReducedScale <= 0.01 ? "muted" : "reduced-strength", measuredDelta));
            return;
        }
        double virtualImprovement = estimatedFullImprovementDb(snapshot.targetComplex());
        activeVerificationPassed = true;
        activeVerificationFailed = false;
        previousCommand = command;
        previousResidual = onResidual;
        currentImprovementDb = Double.isFinite(virtualImprovement)
                ? Math.max(measuredDelta, virtualImprovement) : measuredDelta;
        smoothedVirtualImprovementDb = currentImprovementDb;
        virtualVerificationFailures = 0;
        lastAuditCompletedMs = nowMs;
        stage = Stage.RUNNING;
        stageStartedMs = nowMs;
        status = String.format(Locale.US,
                "%s %.2f Hz · %.1f dB verified %s benefit",
                label, frequencyHz, measuredDelta,
                auditReducedScale <= 0.01 ? "on/off" : "full/reduced");
    }

    private void rejectActiveVerification(String reason) {
        command = Complex.ZERO;
        auditCommand = Complex.ZERO;
        activeVerificationPassed = false;
        activeVerificationFailed = true;
        currentImprovementDb = Double.NaN;
        smoothedVirtualImprovementDb = Double.NaN;
        virtualVerificationFailures = 0;
        stage = Stage.IDLE;
        status = label + ": " + reason + "; muted and quarantined";
    }

    private long roomAuditIntervalMs() {
        long spread = Math.floorMod(Math.round(frequencyHz * 37.0), ROOM_AUDIT_SPREAD_MS);
        return ROOM_AUDIT_BASE_INTERVAL_MS + spread;
    }

    private void reject(String reason) {
        command = Complex.ZERO;
        currentImprovementDb = Double.NaN;
        stage = Stage.IDLE;
        status = label + ": " + reason + "; muted";
    }

    public synchronized Output output() { return new Output(frequencyHz, command.magnitude(), command.phaseRadians(), status); }
    public synchronized boolean needsDiscoveryScan() { return stage == Stage.LISTENING; }
    public synchronized boolean isCalibratingOrRunning() { return stage != Stage.IDLE; }
    public synchronized String stageName() { return stage.name(); }
    public synchronized String label() { return label; }
    public synchronized double currentImprovementDb() { return currentImprovementDb; }
    public synchronized Complex secondaryPathEstimate() { return secondaryPath; }
    public synchronized boolean hasUsableSecondaryPathEstimate() { return secondaryPath.magnitude() >= 1.0e-5; }
    public synchronized boolean hasPassedActiveVerification() { return activeVerificationPassed; }
    public synchronized boolean activeVerificationFailed() { return activeVerificationFailed; }
    private boolean targetMatches(SpectrumSnapshot snapshot) { return Math.abs(snapshot.targetFrequencyHz() - frequencyHz) < 0.035; }
    private long elapsed(long nowMs) { return nowMs - stageStartedMs; }

    private String improvementStatus(double residual) {
        double improvement = 20.0 * Math.log10(Math.max(baselineResidual, 1.0e-9)
                / Math.max(residual, 1.0e-9));
        currentImprovementDb = improvement;
        return String.format(Locale.US, "%s %.2f Hz · %.1f dB reduction", label, frequencyHz, improvement);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
