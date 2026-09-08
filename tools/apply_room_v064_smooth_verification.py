from pathlib import Path


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


root = Path(__file__).resolve().parents[1]

auto_path = root / "anclab/src/main/java/com/p38/anclab/dsp/AutoController.java"
auto = auto_path.read_text()

auto = replace_once(auto, """    private static final double ROOM_MIN_VERIFIED_REDUCTION_DB = 1.0;
    private static final long ROOM_AUDIT_BASE_INTERVAL_MS = 4500L;
    private static final long ROOM_AUDIT_SPREAD_MS = 2200L;
""", """    private static final double ROOM_MIN_VERIFIED_REDUCTION_DB = 1.0;
    private static final long ROOM_AUDIT_BASE_INTERVAL_MS = 60000L;
    private static final long ROOM_AUDIT_SPREAD_MS = 15000L;
    private static final double ROOM_SOFT_AUDIT_SCALE = 0.35;
    private static final double ROOM_SOFT_AUDIT_MIN_DELTA_DB = 0.35;
    private static final double ROOM_VIRTUAL_VERIFY_MIN_DB = 0.45;
    private static final int ROOM_VIRTUAL_VERIFY_FAILURE_WINDOWS = 12;
    private static final double ROOM_VIRTUAL_VERIFY_SMOOTHING = 0.18;
""", "room verification constants")

auto = replace_once(auto, """    private boolean activeVerificationPassed;
    private boolean activeVerificationFailed;
""", """    private boolean activeVerificationPassed;
    private boolean activeVerificationFailed;
    private double auditReducedScale;
    private double auditRequiredReductionDb = ROOM_MIN_VERIFIED_REDUCTION_DB;
    private double smoothedVirtualImprovementDb = Double.NaN;
    private int virtualVerificationFailures;
""", "room verification fields")

auto = replace_once(auto, """        activeVerificationPassed = false;
        activeVerificationFailed = false;
""", """        activeVerificationPassed = false;
        activeVerificationFailed = false;
        auditReducedScale = 0.0;
        auditRequiredReductionDb = ROOM_MIN_VERIFIED_REDUCTION_DB;
        smoothedVirtualImprovementDb = Double.NaN;
        virtualVerificationFailures = 0;
""", "room verification reset")

auto = replace_once(auto, """            beginRoomAudit(nowMs, "certifying cancellation against a muted baseline…");
""", """            beginRoomAudit(nowMs, "certifying cancellation against a muted baseline…",
                    0.0, ROOM_MIN_VERIFIED_REDUCTION_DB);
""", "initial room audit")

auto = replace_once(auto, """        if (directErrorLearning && activeVerificationPassed
                && nowMs - lastAuditCompletedMs >= roomAuditIntervalMs()) {
            beginRoomAudit(nowMs, "re-checking ANC on versus muted…");
            return;
        }
""", """        if (directErrorLearning && activeVerificationPassed) {
            if (updateRoomVirtualVerification(snapshot, nowMs)) return;
            if (nowMs - lastAuditCompletedMs >= roomAuditIntervalMs()) {
                beginRoomAudit(nowMs, "gently re-checking full versus reduced ANC…",
                        ROOM_SOFT_AUDIT_SCALE, ROOM_SOFT_AUDIT_MIN_DELTA_DB);
                return;
            }
        }
""", "running room verification")

auto = replace_once(auto, """            double stepSize = directErrorLearning
                    ? 0.10 * (1.0 - agileWeight) + 0.28 * agileWeight
                    : 0.06 * (1.0 - agileWeight) + 0.20 * agileWeight;
            double normalization = secondaryPath.magnitudeSquared() + 1.0e-10;
            double correctionLimit = maximumGain * (directErrorLearning ? 0.24 : 0.18);
""", """            double stepSize = directErrorLearning
                    ? 0.07 * (1.0 - agileWeight) + 0.16 * agileWeight
                    : 0.06 * (1.0 - agileWeight) + 0.20 * agileWeight;
            double normalization = secondaryPath.magnitudeSquared() + 1.0e-10;
            double correctionLimit = maximumGain * (directErrorLearning ? 0.12 : 0.18);
""", "room adaptation damping")

auto = replace_once(auto, """    private void beginRoomAudit(long nowMs, String message) {
        auditCommand = command;
        command = Complex.ZERO;
        auditOffResidual = Complex.ZERO;
        stage = Stage.AUDIT_OFF;
        stageStartedMs = nowMs;
        status = label + ": " + message;
    }

    private void updateAuditOff(SpectrumSnapshot snapshot, long nowMs) {
        command = Complex.ZERO;
""", """    private boolean updateRoomVirtualVerification(SpectrumSnapshot snapshot, long nowMs) {
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
""", "soft room audit setup")

old_update_audit_on = """    private void updateAuditOn(SpectrumSnapshot snapshot, long nowMs) {
        if (elapsed(nowMs) < settleMs() || !targetMatches(snapshot)) return;
        double onResidual = snapshot.targetComplex().magnitude();
        double improvement = 20.0 * Math.log10(Math.max(baselineResidual, 1.0e-9)
                / Math.max(onResidual, 1.0e-9));
        if (!Double.isFinite(improvement) || improvement < ROOM_MIN_VERIFIED_REDUCTION_DB) {
            rejectActiveVerification(String.format(Locale.US,
                    "muted A/B check found only %.1f dB reduction", improvement));
            return;
        }
        activeVerificationPassed = true;
        activeVerificationFailed = false;
        previousCommand = command;
        previousResidual = onResidual;
        currentImprovementDb = improvement;
        lastAuditCompletedMs = nowMs;
        stage = Stage.RUNNING;
        stageStartedMs = nowMs;
        status = String.format(Locale.US,
                "%s %.2f Hz · %.1f dB verified on/off reduction", label, frequencyHz, improvement);
    }
"""
new_update_audit_on = """    private void updateAuditOn(SpectrumSnapshot snapshot, long nowMs) {
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
"""
auto = replace_once(auto, old_update_audit_on, new_update_audit_on, "soft room audit result")

auto = replace_once(auto, """        activeVerificationPassed = false;
        activeVerificationFailed = true;
        currentImprovementDb = Double.NaN;
        stage = Stage.IDLE;
""", """        activeVerificationPassed = false;
        activeVerificationFailed = true;
        currentImprovementDb = Double.NaN;
        smoothedVirtualImprovementDb = Double.NaN;
        virtualVerificationFailures = 0;
        stage = Stage.IDLE;
""", "active verification rejection reset")

auto_path.write_text(auto)

bank_path = root / "anclab/src/main/java/com/p38/anclab/dsp/VehicleNarrowbandBank.java"
bank = bank_path.read_text()

bank = replace_once(bank, """    private static final long DIRECT_FEEDBACK_AUDIT_RETRY_MS=60000;
""", """    private static final long DIRECT_FEEDBACK_AUDIT_RETRY_MS=60000;
    private static final int ROOM_MAX_DISCOVERED_LANES=3;
    private static final double ROOM_OWNERSHIP_RADIUS_HZ=8.0;
    private static final long ROOM_DISCOVERY_INACTIVE_STALE_MS=15000;
    private static final double ROOM_COEFFICIENT_SMOOTHING_SECONDS=0.180;
    private static final double ROOM_FREQUENCY_SMOOTHING_SECONDS=0.350;
""", "room lane constants")

bank = replace_once(bank, """    private synchronized double perLaneLimit(){return Math.max(0.0001,totalCeiling*Math.max(0.02f,userScale)/effectiveLaneCount());}
""", """    private synchronized double perLaneLimit(){
        int budgetLanes=directFeedbackLearning?ROOM_MAX_DISCOVERED_LANES:effectiveLaneCount();
        return Math.max(0.0001,totalCeiling*Math.max(0.02f,userScale)/budgetLanes);
    }
""", "stable room lane budget")

bank = replace_once(bank, """        double value=0.0;
        double coefficientSmoothing=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.030));
        double frequencySmoothing=1.0-Math.exp(-1.0/(SAMPLE_RATE*0.025));
""", """        double value=0.0;
        double coefficientSeconds=directFeedbackLearning?ROOM_COEFFICIENT_SMOOTHING_SECONDS:0.030;
        double frequencySeconds=directFeedbackLearning?ROOM_FREQUENCY_SMOOTHING_SECONDS:0.025;
        double coefficientSmoothing=1.0-Math.exp(-1.0/(SAMPLE_RATE*coefficientSeconds));
        double frequencySmoothing=1.0-Math.exp(-1.0/(SAMPLE_RATE*frequencySeconds));
""", "room oscillator smoothing")

bank = replace_once(bank, """            if(gain<=1e-5&&now-l.lastStrongMs>DISCOVERY_INACTIVE_STALE_MS){
""", """            long inactiveStaleMs=directFeedbackLearning
                    ?ROOM_DISCOVERY_INACTIVE_STALE_MS:DISCOVERY_INACTIVE_STALE_MS;
            if(gain<=1e-5&&now-l.lastStrongMs>inactiveStaleMs){
""", "room lane stale interval")

bank = replace_once(bank, """    private int fallbackLaneLimit(){
        return broadbandEnabled?MAX_DISCOVERED_LANES_BROADBAND:MAX_DISCOVERED_LANES_NARROWBAND_ONLY;
    }
""", """    private int fallbackLaneLimit(){
        if(directFeedbackLearning)return ROOM_MAX_DISCOVERED_LANES;
        return broadbandEnabled?MAX_DISCOVERED_LANES_BROADBAND:MAX_DISCOVERED_LANES_NARROWBAND_ONLY;
    }
""", "room lane limit")

bank = replace_once(bank, """                if(Math.abs(keepHz-otherHz)<DISCOVERY_COLLISION_RADIUS_HZ){
""", """                double collisionRadius=directFeedbackLearning
                        ?ROOM_OWNERSHIP_RADIUS_HZ:DISCOVERY_COLLISION_RADIUS_HZ;
                if(Math.abs(keepHz-otherHz)<=collisionRadius){
""", "room lane collision radius")

bank = replace_once(bank, """    private boolean nearOwnedFrequency(double hz){
        for(Lane l:lanes)if(l.available&&Math.abs(l.controller.output().frequencyHz()-hz)<DISCOVERY_DUPLICATE_RADIUS_HZ)return true;
        for(DiscoveredLane l:discovered)if(Math.abs(l.controller.output().frequencyHz()-hz)<DISCOVERY_DUPLICATE_RADIUS_HZ)return true;
        return false;
    }
""", """    private boolean nearOwnedFrequency(double hz){
        double radius=directFeedbackLearning?ROOM_OWNERSHIP_RADIUS_HZ:DISCOVERY_DUPLICATE_RADIUS_HZ;
        for(Lane l:lanes)if(l.available&&Math.abs(l.controller.output().frequencyHz()-hz)<=radius)return true;
        for(DiscoveredLane l:discovered)if(Math.abs(l.controller.output().frequencyHz()-hz)<=radius)return true;
        return false;
    }
""", "room ownership radius")

bank_path.write_text(bank)

gradle_path = root / "anclab/build.gradle.kts"
gradle = gradle_path.read_text()
gradle = replace_once(gradle, '        versionCode = 21\n        versionName = "0.6.3-room-verified-feedback-dev"\n',
                      '        versionCode = 22\n        versionName = "0.6.4-room-smoothed-verification-dev"\n',
                      "version bump")
gradle_path.write_text(gradle)

test_path = root / "anclab/src/test/java/com/p38/anclab/dsp/RoomSmoothVerificationTest.java"
test_path.write_text("""package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RoomSmoothVerificationTest {
    private static final double HZ = 56.0;

    @Test public void routineVerificationDoesNotHardMuteEveryFewSeconds() {
        AutoController controller = verifiedController();
        controller.update(snapshot(0.05, 0.0), 8_000);
        assertEquals("RUNNING", controller.stageName());
        assertTrue(controller.output().gain() > 0.0);
    }

    @Test public void weakVirtualBenefitTriggersReducedStrengthAudit() {
        AutoController controller = verifiedController();
        Complex command = controller.output().coefficient();
        Complex equalBenefitResidual = command.multiply(0.5);
        long now = 3_500;
        for (int i = 0; i < 12; i++) {
            now += 100;
            controller.update(snapshot(equalBenefitResidual.re(), equalBenefitResidual.im()), now);
        }
        assertEquals("AUDIT_OFF", controller.stageName());
        assertTrue("soft audit must retain some ANC instead of hard muting",
                controller.output().gain() > 0.0);
        assertTrue(controller.output().gain() < command.magnitude());
    }

    private static AutoController verifiedController() {
        AutoController controller = new AutoController();
        controller.setDirectErrorLearning(true);
        controller.startTrackingWithSecondaryPath(1_000, 0.02, HZ,
                "Room", false, new Complex(1.0, 0.0));
        controller.update(snapshot(1.0, 0.0), 1_500);
        controller.update(snapshot(0.45, 0.0), 2_000);
        controller.update(snapshot(0.35, 0.0), 2_500);
        controller.update(snapshot(0.80, 0.0), 3_000);
        controller.update(snapshot(0.45, 0.0), 3_500);
        assertEquals("RUNNING", controller.stageName());
        return controller;
    }

    private static SpectrumSnapshot snapshot(double real, double imag) {
        Complex target = new Complex(real, imag);
        return new SpectrumSnapshot(HZ, target.magnitude(), -35, 12,
                HZ, target, -35, -30, 2.0);
    }
}
""")
