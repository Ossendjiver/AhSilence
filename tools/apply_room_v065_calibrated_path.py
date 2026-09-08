from pathlib import Path


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


root = Path(__file__).resolve().parents[1]

# --- Secondary-path frequency response from the already measured route calibration. ---
response_path = root / "anclab/src/main/java/com/p38/anclab/dsp/SecondaryPathFrequencyResponse.java"
response_path.write_text("""package com.p38.anclab.dsp;

/** Converts the measured route-calibration FIR plus transport delay into a complex narrowband path. */
public final class SecondaryPathFrequencyResponse {
    private SecondaryPathFrequencyResponse() { }

    public static Complex at(float[] fir, int delaySamples, int sampleRateHz, double frequencyHz) {
        if (fir == null || fir.length == 0 || sampleRateHz <= 0 || !Double.isFinite(frequencyHz))
            return Complex.ZERO;
        double omega = 2.0 * Math.PI * frequencyHz / sampleRateHz;
        double real = 0.0, imaginary = 0.0;
        for (int k = 0; k < fir.length; k++) {
            double phase = -omega * (Math.max(0, delaySamples) + k);
            real += fir[k] * Math.cos(phase);
            imaginary += fir[k] * Math.sin(phase);
        }
        return new Complex(real, imaginary);
    }
}
""")

# --- Room controllers: never fall back to a blind audible path probe. ---
auto_path = root / "anclab/src/main/java/com/p38/anclab/dsp/AutoController.java"
auto = auto_path.read_text()

auto = replace_once(auto, """    private void beginProbe(long nowMs) {
        double probeGain = Math.min(maximumGain, Math.max(0.0002, maximumGain * 0.5));
""", """    private void beginProbe(long nowMs) {
        if (directErrorLearning) {
            rejectActiveVerification("no trustworthy calibrated secondary path; lane quarantined");
            return;
        }
        double probeGain = Math.min(maximumGain, Math.max(0.0002, maximumGain * 0.5));
""", "disable Room blind probe")

old_learned_fallback = """            if (usingLearnedSecondaryPath) {
                usingLearnedSecondaryPath = false;
                command = Complex.ZERO;
                beginProbe(nowMs);
                return;
            }
"""
auto = replace_once(auto, old_learned_fallback, """            if (usingLearnedSecondaryPath) {
                usingLearnedSecondaryPath = false;
                if (directErrorLearning) {
                    rejectActiveVerification("calibrated path increased the measured error");
                    return;
                }
                command = Complex.ZERO;
                beginProbe(nowMs);
                return;
            }
""", "Room half-strength failure quarantine")
auto = replace_once(auto, old_learned_fallback, """            if (usingLearnedSecondaryPath) {
                usingLearnedSecondaryPath = false;
                if (directErrorLearning) {
                    rejectActiveVerification("calibrated path did not produce repeatable reduction");
                    return;
                }
                command = Complex.ZERO;
                beginProbe(nowMs);
                return;
            }
""", "Room full-strength failure quarantine")
auto_path.write_text(auto)

# --- Narrowband bank: seed Room lanes from route calibration and freeze discovery once cancellation starts. ---
bank_path = root / "anclab/src/main/java/com/p38/anclab/dsp/VehicleNarrowbandBank.java"
bank = bank_path.read_text()

bank = replace_once(bank, """    private static final double ROOM_FREQUENCY_SMOOTHING_SECONDS=0.350;
""", """    private static final double ROOM_FREQUENCY_SMOOTHING_SECONDS=0.350;
    private static final double ROOM_MIN_CALIBRATED_PATH_MAGNITUDE=1.0e-5;
""", "Room calibrated path constant")

bank = replace_once(bank, """    private final boolean directFeedbackLearning;

    private int ringPos=0,ringCount=0,decimator=0,sinceAnalysis=0,discoveryAnalysisCounter=0;
""", """    private final boolean directFeedbackLearning;
    private final float[] calibratedSecondaryPathFir;
    private final int calibratedDelaySamples;
    private final int calibratedSampleRateHz;

    private int ringPos=0,ringCount=0,decimator=0,sinceAnalysis=0,discoveryAnalysisCounter=0;
""", "Room calibrated path fields")

old_ctor = """    public VehicleNarrowbandBank(List<MechanicalFrequency> models,VehicleTelemetryRuntime telemetry,
                                 float safeOutputCeiling,float userScale,
                                 double cancellationMinimumHz,double cancellationMaximumHz,
                                 String routeKey,List<VehicleCancellationRecipe> recipes,boolean directFeedbackLearning){
        this.telemetry=telemetry;
        this.directFeedbackLearning=directFeedbackLearning;
        totalCeiling=clamp(Math.abs(safeOutputCeiling),0.005f,0.15f);
        this.userScale=clamp(userScale,0f,1f);
        this.cancellationMinimumHz=Math.max(FrequencyLanePolicy.MONITOR_MINIMUM_HZ,
                Math.min(FrequencyLanePolicy.MONITOR_MAXIMUM_HZ,cancellationMinimumHz));
        this.cancellationMaximumHz=Math.max(this.cancellationMinimumHz,
                Math.min(FrequencyLanePolicy.MONITOR_MAXIMUM_HZ,cancellationMaximumHz));
        this.routeKey=routeKey==null?"":routeKey;
        recipeBook=new VehicleRecipeBook(recipes);
        rebuild(models,System.currentTimeMillis());
    }
"""
new_ctor = """    public VehicleNarrowbandBank(List<MechanicalFrequency> models,VehicleTelemetryRuntime telemetry,
                                 float safeOutputCeiling,float userScale,
                                 double cancellationMinimumHz,double cancellationMaximumHz,
                                 String routeKey,List<VehicleCancellationRecipe> recipes,boolean directFeedbackLearning){
        this(models,telemetry,safeOutputCeiling,userScale,cancellationMinimumHz,cancellationMaximumHz,
                routeKey,recipes,directFeedbackLearning,null,0,SAMPLE_RATE);
    }

    public VehicleNarrowbandBank(List<MechanicalFrequency> models,VehicleTelemetryRuntime telemetry,
                                 float safeOutputCeiling,float userScale,
                                 double cancellationMinimumHz,double cancellationMaximumHz,
                                 String routeKey,List<VehicleCancellationRecipe> recipes,boolean directFeedbackLearning,
                                 float[] calibratedSecondaryPathFir,int calibratedDelaySamples,int calibratedSampleRateHz){
        this.telemetry=telemetry;
        this.directFeedbackLearning=directFeedbackLearning;
        this.calibratedSecondaryPathFir=calibratedSecondaryPathFir==null?new float[0]:calibratedSecondaryPathFir.clone();
        this.calibratedDelaySamples=Math.max(0,calibratedDelaySamples);
        this.calibratedSampleRateHz=calibratedSampleRateHz>0?calibratedSampleRateHz:SAMPLE_RATE;
        totalCeiling=clamp(Math.abs(safeOutputCeiling),0.005f,0.15f);
        this.userScale=clamp(userScale,0f,1f);
        this.cancellationMinimumHz=Math.max(FrequencyLanePolicy.MONITOR_MINIMUM_HZ,
                Math.min(FrequencyLanePolicy.MONITOR_MAXIMUM_HZ,cancellationMinimumHz));
        this.cancellationMaximumHz=Math.max(this.cancellationMinimumHz,
                Math.min(FrequencyLanePolicy.MONITOR_MAXIMUM_HZ,cancellationMaximumHz));
        this.routeKey=routeKey==null?"":routeKey;
        recipeBook=new VehicleRecipeBook(recipes);
        rebuild(models,System.currentTimeMillis());
    }
"""
bank = replace_once(bank, old_ctor, new_ctor, "Room calibrated constructor")

bank = replace_once(bank, """        if(++discoveryAnalysisCounter>=DISCOVERY_SCAN_EVERY_ANALYSES){
            discoveryAnalysisCounter=0;
""", """        boolean allowAdmission=!directFeedbackLearning||!hasActiveRoomCancellationController();
        if(++discoveryAnalysisCounter>=DISCOVERY_SCAN_EVERY_ANALYSES&&allowAdmission){
            discoveryAnalysisCounter=0;
""", "freeze Room discovery while cancelling")

old_start_discovered = """    private void startDiscoveredController(DiscoveredLane lane,long now){
        lane.controller.setDirectErrorLearning(directFeedbackLearning);
        VehicleCancellationRecipe recipe=recipeBook.find(routeKey,"discovered",
                MechanicalFrequency.SourceType.FIXED,lane.anchorFrequencyHz,lane.currentFrequencyHz);
        if(recipe==null)lane.controller.startTracking(now,perLaneLimit(),lane.currentFrequencyHz,lane.label,false);
        else lane.controller.startTrackingWithSecondaryPath(now,perLaneLimit(),lane.currentFrequencyHz,
                lane.label,false,recipe.secondaryPath());
        lane.idleSinceMs=0;lane.successUpdates=0;
    }
"""
new_start_discovered = """    private void startDiscoveredController(DiscoveredLane lane,long now){
        lane.controller.setDirectErrorLearning(directFeedbackLearning);
        VehicleCancellationRecipe recipe=recipeBook.find(routeKey,"discovered",
                MechanicalFrequency.SourceType.FIXED,lane.anchorFrequencyHz,lane.currentFrequencyHz);
        Complex seededPath=recipe==null?Complex.ZERO:recipe.secondaryPath();
        if(directFeedbackLearning&&seededPath.magnitude()<ROOM_MIN_CALIBRATED_PATH_MAGNITUDE)
            seededPath=SecondaryPathFrequencyResponse.at(calibratedSecondaryPathFir,calibratedDelaySamples,
                    calibratedSampleRateHz,lane.currentFrequencyHz);
        if(seededPath.magnitude()>=ROOM_MIN_CALIBRATED_PATH_MAGNITUDE){
            lane.controller.startTrackingWithSecondaryPath(now,perLaneLimit(),lane.currentFrequencyHz,
                    lane.label,false,seededPath);
        }else if(directFeedbackLearning){
            lane.controller.stop();
            lane.auditRejected=true;
            lane.auditRejectedMs=now;
        }else{
            lane.controller.startTracking(now,perLaneLimit(),lane.currentFrequencyHz,lane.label,false);
        }
        lane.idleSinceMs=0;lane.successUpdates=0;
    }
"""
bank = replace_once(bank, old_start_discovered, new_start_discovered, "seed Room lane from calibration")

bank = replace_once(bank, """    private int fallbackLaneLimit(){
        if(directFeedbackLearning)return ROOM_MAX_DISCOVERED_LANES;
""", """    private boolean hasActiveRoomCancellationController(){
        for(DiscoveredLane lane:discovered){
            if(!lane.cancellable||lane.auditRejected)continue;
            if(!"IDLE".equals(lane.controller.stageName())||lane.controller.output().gain()>1e-7)return true;
        }
        return false;
    }

    private int fallbackLaneLimit(){
        if(directFeedbackLearning)return ROOM_MAX_DISCOVERED_LANES;
""", "Room discovery activity helper")

bank = bank.replace("Room direct feedback · auto-discovering stable 8–200 Hz lines",
                    "Room calibrated-path feedback · quiet-discovering stable 8–200 Hz lines")
bank_path.write_text(bank)

# --- Runtime: Room avoids sub-40 Hz speaker excursion and supplies calibration FIR to the bank. ---
audio_path = root / "anclab/src/main/java/com/p38/anclab/audio/AudioEngine.java"
audio = audio_path.read_text()

audio = replace_once(audio, """    private static final int GRAPH_DECIMATION=24;

    private enum Mode { NONE, HEADPHONES, VEHICLE, ROOM }
""", """    private static final int GRAPH_DECIMATION=24;
    private static final float ROOM_MIN_CANCELLATION_HZ=40f;

    private enum Mode { NONE, HEADPHONES, VEHICLE, ROOM }
""", "Room minimum frequency constant")

audio = replace_once(audio,
    "c.minimumCancellationHz=(ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)||ProfileStore.PROFILE_ROOM.equals(activeProfile))?15f:20f;",
    "c.minimumCancellationHz=ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)?15f:ProfileStore.PROFILE_ROOM.equals(activeProfile)?ROOM_MIN_CANCELLATION_HZ:20f;",
    "Room calibration minimum frequency")

audio = replace_once(audio, """                        calibration.minimumCancellationHz,calibration.maximumCancellationHz,
                        vehicleRecipeRouteKey(),profiles.loadCancellationRecipes(profileId),room);
""", """                        room?Math.max(ROOM_MIN_CANCELLATION_HZ,calibration.minimumCancellationHz):calibration.minimumCancellationHz,
                        calibration.maximumCancellationHz,
                        vehicleRecipeRouteKey(),profiles.loadCancellationRecipes(profileId),room,
                        room?calibration.secondaryPath:null,room?calibration.delaySamples:0,calibration.sampleRateHz);
""", "pass Room calibration path to bank")

audio = replace_once(audio, """                if(room)safetyStatus="Room direct-feedback loop ready · measured microphone residual feeds adaptive cancellation and recipes";
""", """                if(room)safetyStatus="Room calibrated-path feedback ready · quiet discovery · no blind acoustic probes · 40–200 Hz narrowband";
""", "Room status")

audio = replace_once(audio, """        return activeProfile+"|in="+inputDeviceId+"|out="+outputDeviceId+"|cal="+revision;
""", """        String key=activeProfile+"|in="+inputDeviceId+"|out="+outputDeviceId+"|cal="+revision;
        return ProfileStore.PROFILE_ROOM.equals(activeProfile)?key+"|roomPath=cal-v1":key;
""", "invalidate pre-calibrated Room recipes")
audio_path.write_text(audio)

# --- Version and tests. ---
gradle_path = root / "anclab/build.gradle.kts"
gradle = gradle_path.read_text()
gradle = replace_once(gradle, '        versionCode = 22\n        versionName = "0.6.4-room-smoothed-verification-dev"\n',
                      '        versionCode = 23\n        versionName = "0.6.5-room-calibrated-path-dev"\n',
                      "version bump")
gradle = gradle.replace("// Room ANC v0.6.3 includes recurring muted A/B effectiveness verification.",
                        "// Room ANC v0.6.5 seeds narrowband control from measured route calibration and forbids blind Room probes.")
gradle_path.write_text(gradle)

test_response = root / "anclab/src/test/java/com/p38/anclab/dsp/SecondaryPathFrequencyResponseTest.java"
test_response.write_text("""package com.p38.anclab.dsp;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SecondaryPathFrequencyResponseTest {
    @Test public void includesTransportDelayPhase() {
        Complex h=SecondaryPathFrequencyResponse.at(new float[]{1f},120,48000,100.0);
        assertEquals(0.0,h.re(),1e-6);
        assertEquals(-1.0,h.im(),1e-6);
    }

    @Test public void includesFirMagnitude() {
        Complex h=SecondaryPathFrequencyResponse.at(new float[]{0.5f},0,48000,100.0);
        assertEquals(0.5,h.magnitude(),1e-7);
        assertEquals(0.0,h.phaseRadians(),1e-7);
    }
}
""")

test_probe = root / "anclab/src/test/java/com/p38/anclab/dsp/RoomNoBlindProbeTest.java"
test_probe.write_text("""package com.p38.anclab.dsp;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RoomNoBlindProbeTest {
    private static SpectrumSnapshot snapshot(double magnitude) {
        Complex c=new Complex(magnitude,0.0);
        return new SpectrumSnapshot(50.0,c.magnitude(),-35,12,50.0,c,-35,-30,2.0);
    }

    @Test public void roomWithoutCalibratedPathNeverStartsBlindProbe() {
        AutoController c=new AutoController();
        c.setDirectErrorLearning(true);
        c.startTracking(1000,0.02,50.0,"Room",false);
        c.update(snapshot(1.0),1500);
        assertEquals("IDLE",c.stageName());
        assertTrue(c.activeVerificationFailed());
        assertEquals(0.0,c.output().gain(),1e-12);
    }

    @Test public void failedCalibratedPathIsQuarantinedInsteadOfReprobed() {
        AutoController c=new AutoController();
        c.setDirectErrorLearning(true);
        c.startTrackingWithSecondaryPath(1000,0.02,50.0,"Room",false,new Complex(1.0,0.0));
        c.update(snapshot(1.0),1500);
        c.update(snapshot(1.20),2000);
        assertEquals("IDLE",c.stageName());
        assertTrue(c.activeVerificationFailed());
        assertEquals(0.0,c.output().gain(),1e-12);
    }
}
""")
