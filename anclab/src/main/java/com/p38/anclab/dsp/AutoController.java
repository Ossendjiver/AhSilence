package com.p38.anclab.dsp;

import java.util.Locale;

/** Conservative, bounded calibration and tracking for one sinusoidal cancellation lane. */
public final class AutoController {
    public record Output(double frequencyHz, double gain, double phaseRadians, String status) {
        public Complex coefficient() { return Complex.polar(gain, phaseRadians); }
    }

    private enum Stage {
        IDLE, LISTENING, BASELINE, VERIFY_RECIPE, PROBE_POSITIVE, PROBE_NEGATIVE,
        VERIFY_HALF, VERIFY_FULL, VERIFY_CONFIRM_BASELINE, VERIFY_CONFIRM_OUTPUT,
        RUNNING, RUNNING_AB_BASELINE, RUNNING_AB_OUTPUT, VERIFY_FINE, SEEK_FIRST, SEEK_SECOND,
        SEEK_RETURN, FOLLOW_VERIFY, REACQUIRE_BASELINE
    }

    private static final long LISTEN_MS = 1500;
    private static final long MINIMUM_SETTLE_MS = 650;
    // SpectrumAnalyzer uses six cycles, bounded to 300-750 ms. After an output change, wait for
    // the measured transport delay plus that complete observation window and an acoustic guard.
    // The old fixed 650 ms wait mixed old and new commands on the measured 413-452 ms Bluetooth
    // route; v0.6's 200-300 ms waits measured almost entirely the preceding command.
    private static final long MINIMUM_OBSERVATION_WINDOW_MS = 300;
    private static final long MAXIMUM_OBSERVATION_WINDOW_MS = 750;
    private static final long OUTPUT_SETTLE_GUARD_MS = 100;
    private static final long MAXIMUM_SETTLE_MS = 3000;
    private static final long ADAPT_INTERVAL_MS = 350;
    private static final double SEEK_STEP_HZ = 0.08;
    private static final double FOLLOW_DEADBAND_HZ = 0.04;
    private static final double CALIBRATION_RETUNE_HYSTERESIS_HZ = 0.55;
    private static final double RUNNING_VERIFY_HYSTERESIS_HZ = 0.20;
    private static final double MINIMUM_CONFIRMED_REDUCTION_DB = 0.75;
    private static final double LOUDER_THAN_MUTED_DB = 1.5;
    private static final double LOUDER_THAN_BASELINE_RATIO = Math.pow(10.0, LOUDER_THAN_MUTED_DB / 20.0);
    private static final int REQUIRED_REPEATABLE_REDUCTIONS = 2;
    private static final long RUNNING_AB_INTERVAL_MS = 15000;

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
    private String label = "Dominant";
    private double currentImprovementDb = Double.NaN;
    private int rejectedAdaptations;
    private double seekCentreFrequency;
    private double seekCentreResidual;
    private double seekDirection;
    private double seekBestFrequency;
    private double seekBestResidual;
    private double outputLatencyMs;
    private Complex confirmationCommand = Complex.ZERO;
    private Complex fullCandidateCommand = Complex.ZERO;
    private Complex runningAbCommand = Complex.ZERO;
    private long lastRunningAbMs;
    private int repeatableReductions;
    private int mutedReacquisitions;

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
        this.frequencyHz = clamp(frequencyHz, 8.0, 200.0);
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
        confirmationCommand = Complex.ZERO;
        fullCandidateCommand = Complex.ZERO;
        runningAbCommand = Complex.ZERO;
        previousResidual = Double.POSITIVE_INFINITY;
        baselineResidual = Double.POSITIVE_INFINITY;
        currentImprovementDb = Double.NaN;
        rejectedAdaptations = 0;
        lastRunningAbMs = nowMs;
        repeatableReductions = 0;
        mutedReacquisitions = 0;
    }

    public synchronized void stop() {
        stage = Stage.IDLE;
        command = Complex.ZERO;
        status = label + ": stopped";
    }

    public synchronized void setMaximumGain(double maximumGain) {
        this.maximumGain = clamp(maximumGain, 0.0001, 0.15);
        command = command.clampMagnitude(this.maximumGain);
    }

    /** Configure the measured output-to-error-microphone transport delay for clean A/B windows. */
    public synchronized void setOutputLatencyMs(double outputLatencyMs) {
        this.outputLatencyMs = Double.isFinite(outputLatencyMs)
                ? clamp(outputLatencyMs, 0.0, MAXIMUM_SETTLE_MS - MAXIMUM_OBSERVATION_WINDOW_MS)
                : 0.0;
    }

    public synchronized long observationSettleMs() { return settleMs(); }

    /**
     * Follow a measured/predicted tone without continuously invalidating an in-flight path probe.
     * During BASELINE/PROBE/VERIFY calibration the controller holds its acoustic centre through small
     * tracker jitter and only restarts once the requested move is large enough to represent a real
     * retune. Once RUNNING, small motion is followed continuously; only a larger jump gets a settled
     * verification state.
     */
    public synchronized void followFrequency(double requestedHz, long nowMs) {
        requestedHz = clamp(requestedHz, 8.0, 200.0);
        double delta = requestedHz - frequencyHz;
        double distance = Math.abs(delta);
        if (distance < FOLLOW_DEADBAND_HZ) return;

        if (stage == Stage.RUNNING) {
            frequencyHz = requestedHz;
            if (distance >= RUNNING_VERIFY_HYSTERESIS_HZ) {
                repeatableReductions = 0;
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
            case VERIFY_CONFIRM_BASELINE -> updateConfirmationBaseline(snapshot, nowMs);
            case VERIFY_CONFIRM_OUTPUT -> updateConfirmationOutput(snapshot, nowMs);
            case RUNNING -> updateRunning(snapshot, nowMs);
            case RUNNING_AB_BASELINE -> updateRunningAbBaseline(snapshot, nowMs);
            case RUNNING_AB_OUTPUT -> updateRunningAbOutput(snapshot, nowMs);
            case VERIFY_FINE -> updateFine(snapshot, nowMs);
            case SEEK_FIRST -> updateSeekFirst(snapshot, nowMs);
            case SEEK_SECOND -> updateSeekSecond(snapshot, nowMs);
            case SEEK_RETURN -> updateSeekReturn(snapshot, nowMs);
            case FOLLOW_VERIFY -> updateFollow(snapshot, nowMs);
            case REACQUIRE_BASELINE -> updateReacquireBaseline(snapshot, nowMs);
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
                command = Complex.ZERO;
                beginProbe(nowMs);
                return;
            }
            reject("trial became louder");
            return;
        }
        previousCommand = command;
        previousResidual = residual;
        fullCandidateCommand = baseline.negate().divide(secondaryPath).clampMagnitude(maximumGain);
        confirmationCommand = command;
        repeatableReductions = 0;
        command = Complex.ZERO;
        stage = Stage.VERIFY_CONFIRM_BASELINE;
        stageStartedMs = nowMs;
        status = label + ": repeating muted/active check before increasing gain…";
    }

    private void updateFull(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        double residual = snapshot.targetComplex().magnitude();
        if (residual > previousResidual * 1.03) {
            command = previousCommand;
            residual = previousResidual;
        }
        if (residual >= baselineResidual * 0.99) {
            if (usingLearnedSecondaryPath) {
                usingLearnedSecondaryPath = false;
                command = Complex.ZERO;
                beginProbe(nowMs);
                return;
            }
            reject("no repeatable reduction");
            return;
        }
        // Certify against a new adjacent muted baseline, not the older baseline used to calculate
        // this command. This prevents a transient reduction from becoming a saved recipe.
        usingLearnedSecondaryPath = false;
        confirmationCommand = command;
        repeatableReductions = 0;
        command = Complex.ZERO;
        stage = Stage.VERIFY_CONFIRM_BASELINE;
        stageStartedMs = nowMs;
        status = label + ": confirming against a fresh muted baseline…";
    }

    private void updateConfirmationBaseline(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        baseline = snapshot.targetComplex();
        baselineResidual = baseline.magnitude();
        if (baselineResidual < 1.0e-7) { reject("confirmation baseline was silent"); return; }
        command = confirmationCommand;
        stage = Stage.VERIFY_CONFIRM_OUTPUT;
        stageStartedMs = nowMs;
        status = label + ": confirming the reduction…";
    }

    private void updateConfirmationOutput(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        double residual = snapshot.targetComplex().magnitude();
        double improvement = 20.0 * Math.log10(Math.max(baselineResidual, 1.0e-9)
                / Math.max(residual, 1.0e-9));
        if (residual > baselineResidual * LOUDER_THAN_BASELINE_RATIO) {
            beginMutedReacquisition(nowMs, "active trial was more than 1.5 dB louder");
            return;
        }
        if (!Double.isFinite(improvement) || improvement < MINIMUM_CONFIRMED_REDUCTION_DB) {
            reject("reduction did not repeat");
            return;
        }
        previousResidual = residual;
        previousCommand = command;
        repeatableReductions++;
        if (repeatableReductions < REQUIRED_REPEATABLE_REDUCTIONS) {
            confirmationCommand = command;
            command = Complex.ZERO;
            stage = Stage.VERIFY_CONFIRM_BASELINE;
            stageStartedMs = nowMs;
            status = label + ": repeating muted/active verification…";
            return;
        }
        if (fullCandidateCommand.magnitude() > command.magnitude() * 1.02) {
            command = fullCandidateCommand;
            fullCandidateCommand = Complex.ZERO;
            stage = Stage.VERIFY_FULL;
            stageStartedMs = nowMs;
            status = label + ": two reductions confirmed; testing increased gain…";
            return;
        }
        confirmationCommand = Complex.ZERO;
        stage = Stage.RUNNING;
        stageStartedMs = nowMs;
        lastRunningAbMs = nowMs;
        status = improvementStatus(residual);
    }

    private void updateRunning(SpectrumSnapshot snapshot, long nowMs) {
        if (targetMatches(snapshot)) {
            double measuredResidual = snapshot.targetComplex().magnitude();
            // Continuously report achieved attenuation. A clean snapshot more than 1.5 dB above
            // the latest muted reference is enough to mute immediately; output is never allowed to
            // persist merely to confirm that it is making the cabin louder.
            status = improvementStatus(measuredResidual);
            if (measuredResidual > baselineResidual * LOUDER_THAN_BASELINE_RATIO) {
                beginMutedReacquisition(nowMs, "residual became more than 1.5 dB louder");
                return;
            }
            if (nowMs - lastRunningAbMs >= RUNNING_AB_INTERVAL_MS) {
                runningAbCommand = command;
                command = Complex.ZERO;
                stage = Stage.RUNNING_AB_BASELINE;
                stageStartedMs = nowMs;
                status = label + ": periodic A/B · measuring muted residual…";
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
        if (elapsed(nowMs) >= ADAPT_INTERVAL_MS && targetMatches(snapshot)) {
            Complex error = snapshot.targetComplex();
            double residual = error.magnitude();
            double reference = Double.isFinite(previousResidual) ? previousResidual : baselineResidual;
            double innovation = Math.abs(residual - reference) / Math.max(reference, baselineResidual * 0.05);
            double agileWeight = clamp(innovation * 1.5, 0.0, 1.0);
            double stepSize = 0.06 * (1.0 - agileWeight) + 0.20 * agileWeight;
            double normalization = secondaryPath.magnitudeSquared() + 1.0e-10;
            Complex gradient = secondaryPath.conjugate().multiply(error)
                    .multiply(-stepSize / normalization).clampMagnitude(maximumGain * 0.18);
            previousCommand = command;
            previousResidual = residual;
            Complex candidate = command.multiply(0.9995).add(gradient).clampMagnitude(maximumGain);
            if (candidate.magnitude() > command.magnitude()
                    && repeatableReductions < REQUIRED_REPEATABLE_REDUCTIONS)
                candidate = candidate.clampMagnitude(command.magnitude());
            command = candidate;
            stage = Stage.VERIFY_FINE;
            stageStartedMs = nowMs;
            status = label + ": filtered-X adapting phase and level…";
        }
    }

    private void updateRunningAbBaseline(SpectrumSnapshot snapshot, long nowMs) {
        command = Complex.ZERO;
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        baseline = snapshot.targetComplex();
        baselineResidual = baseline.magnitude();
        if (baselineResidual < 1.0e-7 || runningAbCommand.magnitude() < 1.0e-7) {
            reject("periodic A/B baseline was unusable");
            return;
        }
        command = runningAbCommand.clampMagnitude(maximumGain);
        stage = Stage.RUNNING_AB_OUTPUT;
        stageStartedMs = nowMs;
        status = label + ": periodic A/B · measuring active residual…";
    }

    private void updateRunningAbOutput(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        double active = snapshot.targetComplex().magnitude();
        if (active > baselineResidual * LOUDER_THAN_BASELINE_RATIO) {
            beginMutedReacquisition(nowMs, "periodic A/B was more than 1.5 dB louder");
            return;
        }
        double improvement = 20.0 * Math.log10(Math.max(baselineResidual, 1.0e-9)
                / Math.max(active, 1.0e-9));
        if (Double.isFinite(improvement) && improvement >= MINIMUM_CONFIRMED_REDUCTION_DB) {
            repeatableReductions = Math.min(REQUIRED_REPEATABLE_REDUCTIONS, repeatableReductions + 1);
            previousResidual = active;
        } else {
            repeatableReductions = 0;
            command = command.multiply(0.80);
        }
        runningAbCommand = Complex.ZERO;
        lastRunningAbMs = nowMs;
        stage = Stage.RUNNING;
        stageStartedMs = nowMs;
        status = improvementStatus(active);
    }

    private void updateFine(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        Complex measuredCommand = command;
        double residual = snapshot.targetComplex().magnitude();
        if (residual > Math.min(baselineResidual * 1.02, previousResidual * 1.25)) {
            command = previousCommand;
            residual = previousResidual;
            rejectedAdaptations++;
            if (snapshot.targetComplex().magnitude() > baselineResidual * LOUDER_THAN_BASELINE_RATIO) {
                beginMutedReacquisition(nowMs, "adaptation made the residual louder");
                return;
            }
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

    private void beginMutedReacquisition(long nowMs, String reason) {
        command = Complex.ZERO;
        previousCommand = Complex.ZERO;
        confirmationCommand = Complex.ZERO;
        fullCandidateCommand = Complex.ZERO;
        runningAbCommand = Complex.ZERO;
        currentImprovementDb = Double.NaN;
        repeatableReductions = 0;
        mutedReacquisitions++;
        stage = Stage.REACQUIRE_BASELINE;
        stageStartedMs = nowMs;
        status = label + ": " + reason + "; muted and reacquiring phase…";
    }

    private void updateReacquireBaseline(SpectrumSnapshot snapshot, long nowMs) {
        command = Complex.ZERO;
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        baseline = snapshot.targetComplex();
        baselineResidual = baseline.magnitude();
        previousCommand = Complex.ZERO;
        previousResidual = baselineResidual;
        rejectedAdaptations = 0;
        if (baselineResidual < 1.0e-7) { reject("reacquisition baseline was silent"); return; }
        if (secondaryPath.magnitude() < 1.0e-5) { beginProbe(nowMs); return; }
        command = baseline.negate().divide(secondaryPath)
                .clampMagnitude(maximumGain).multiply(0.5);
        usingLearnedSecondaryPath = true;
        stage = Stage.VERIFY_HALF;
        stageStartedMs = nowMs;
        status = label + ": muted baseline captured; validating reacquired phase…";
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
    public synchronized int mutedReacquisitions() { return mutedReacquisitions; }
    public synchronized Complex secondaryPathEstimate() { return secondaryPath; }
    public synchronized boolean hasUsableSecondaryPathEstimate() { return secondaryPath.magnitude() >= 1.0e-5; }
    private boolean targetMatches(SpectrumSnapshot snapshot) { return Math.abs(snapshot.targetFrequencyHz() - frequencyHz) < 0.035; }
    private long settleMs() {
        long cycles = Math.round(6000.0 / Math.max(8.0, frequencyHz));
        long observation = Math.max(MINIMUM_OBSERVATION_WINDOW_MS,
                Math.min(MAXIMUM_OBSERVATION_WINDOW_MS, cycles));
        long clean = Math.round(outputLatencyMs) + observation + OUTPUT_SETTLE_GUARD_MS;
        return Math.min(MAXIMUM_SETTLE_MS, Math.max(MINIMUM_SETTLE_MS, clean));
    }
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
