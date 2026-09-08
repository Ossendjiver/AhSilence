from pathlib import Path


def replace_once(text, old, new, label):
    count=text.count(old)
    if count!=1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old,new,1)

root=Path(__file__).resolve().parents[1]

# AutoController: allow vehicle/room callers to forbid blind acoustic probing independently
# of Room-specific smoothing/adaptation behaviour.
p=root/'anclab/src/main/java/com/p38/anclab/dsp/AutoController.java'
s=p.read_text()
s=replace_once(s,
'''    private boolean directErrorLearning;\n''',
'''    private boolean directErrorLearning;\n    private boolean blindProbesAllowed = true;\n''','AutoController blind-probe field')
s=replace_once(s,
'''    public synchronized void setDirectErrorLearning(boolean enabled) { directErrorLearning = enabled; }\n\n    private long settleMs() { return directErrorLearning ? 420L : SETTLE_MS; }\n''',
'''    public synchronized void setDirectErrorLearning(boolean enabled) { directErrorLearning = enabled; }\n    public synchronized void setBlindProbesAllowed(boolean allowed) { blindProbesAllowed = allowed; }\n    private boolean mustRejectInsteadOfBlindProbe() { return directErrorLearning || !blindProbesAllowed; }\n\n    private long settleMs() { return directErrorLearning ? 420L : SETTLE_MS; }\n''','AutoController blind-probe setter')
s=replace_once(s,
'''    private void beginProbe(long nowMs) {\n        if (directErrorLearning) {\n''',
'''    private void beginProbe(long nowMs) {\n        if (mustRejectInsteadOfBlindProbe()) {\n''','AutoController beginProbe guard')
s=s.replace('''                if (directErrorLearning) {\n                    rejectActiveVerification("calibrated path increased the measured error");\n''',
'''                if (mustRejectInsteadOfBlindProbe()) {\n                    rejectActiveVerification("calibrated path increased the measured error");\n''')
s=s.replace('''                if (directErrorLearning) {\n                    rejectActiveVerification("calibrated path did not produce repeatable reduction");\n''',
'''                if (mustRejectInsteadOfBlindProbe()) {\n                    rejectActiveVerification("calibrated path did not produce repeatable reduction");\n''')
p.write_text(s)

# VehicleNarrowbandBank: use the measured route path for every profile, make microphone-only
# fallback quiet/dominant/verified, but preserve telemetry-specific tracking dynamics.
p=root/'anclab/src/main/java/com/p38/anclab/dsp/VehicleNarrowbandBank.java'
s=p.read_text()
s=replace_once(s,
'''    private static final int ROOM_MAX_DISCOVERED_LANES=3;\n''',
'''    private static final int ROOM_MAX_DISCOVERED_LANES=3;\n    private static final int VERIFIED_VEHICLE_FALLBACK_MAX_DISCOVERED_LANES=4;\n''','verified vehicle fallback lane cap')
s=replace_once(s,
'''    private static final double ROOM_DISCOVERY_TRACK_HALF_WIDTH_HZ=3.50;\n''',
'''    private static final double ROOM_DISCOVERY_TRACK_HALF_WIDTH_HZ=3.50;\n    private static final double VERIFIED_FALLBACK_MATCH_RADIUS_HZ=2.50;\n    private static final double VERIFIED_FALLBACK_SEARCH_HALF_WIDTH_HZ=2.40;\n    private static final double VERIFIED_VEHICLE_TRACK_HALF_WIDTH_HZ=5.00;\n    private static final double VERIFIED_VEHICLE_OWNERSHIP_RADIUS_HZ=3.00;\n''','verified fallback constants')

# Add helpers before analyzeFallbackDiscovery.
anchor='''    private synchronized DiscoveryResult analyzeFallbackDiscovery(float[] window,long first,long now){\n'''
helpers='''    private boolean hasCalibratedSecondaryPath(){\n        return calibratedSecondaryPathFir.length>0&&calibratedSampleRateHz>0;\n    }\n\n    private boolean usesVerifiedFallbackSafety(){\n        return directFeedbackLearning||hasCalibratedSecondaryPath();\n    }\n\n    private Complex calibratedPathAt(double frequencyHz){\n        if(!hasCalibratedSecondaryPath())return Complex.ZERO;\n        return SecondaryPathFrequencyResponse.at(calibratedSecondaryPathFir,calibratedDelaySamples,\n                calibratedSampleRateHz,frequencyHz);\n    }\n\n    private synchronized DiscoveryResult analyzeFallbackDiscovery(float[] window,long first,long now){\n'''
s=replace_once(s,anchor,helpers,'fallback helpers')

s=replace_once(s,
'''        boolean allowAdmission=!directFeedbackLearning||!hasActiveRoomCancellationController();\n''',
'''        boolean verifiedFallback=usesVerifiedFallbackSafety();\n        boolean allowAdmission=!verifiedFallback||!hasActiveFallbackCancellationController();\n''','quiet discovery all calibrated fallback')
s=replace_once(s,
'''            List<BroadbandDetector.Candidate> ready=new ArrayList<>(directFeedbackLearning\n                    ?discoveryDetector.update(peaks,now,ROOM_DISCOVERY_MATCH_RADIUS_HZ)\n                    :discoveryDetector.update(peaks,now));\n            ready.sort((a,b)->{\n                if(directFeedbackLearning){\n''',
'''            List<BroadbandDetector.Candidate> ready=new ArrayList<>(verifiedFallback\n                    ?discoveryDetector.update(peaks,now,VERIFIED_FALLBACK_MATCH_RADIUS_HZ)\n                    :discoveryDetector.update(peaks,now));\n            ready.sort((a,b)->{\n                if(verifiedFallback){\n''','dominant ranking all calibrated fallback')
s=replace_once(s,
'''                if(directFeedbackLearning)lane.tracker.setBounds(\n                        Math.max(8.0,candidate.frequencyHz()-ROOM_DISCOVERY_TRACK_HALF_WIDTH_HZ),\n                        Math.min(200.0,candidate.frequencyHz()+ROOM_DISCOVERY_TRACK_HALF_WIDTH_HZ));\n''',
'''                if(verifiedFallback){\n                    double halfWidth=directFeedbackLearning?ROOM_DISCOVERY_TRACK_HALF_WIDTH_HZ\n                            :VERIFIED_VEHICLE_TRACK_HALF_WIDTH_HZ;\n                    lane.tracker.setBounds(Math.max(8.0,candidate.frequencyHz()-halfWidth),\n                            Math.min(200.0,candidate.frequencyHz()+halfWidth));\n                }\n''','verified fallback tracker bounds')
s=replace_once(s,
'''            double discoverySearchHalfWidth=directFeedbackLearning\n                    ?ROOM_DISCOVERY_SEARCH_HALF_WIDTH_HZ:DISCOVERY_SEARCH_HALF_WIDTH_HZ;\n''',
'''            double discoverySearchHalfWidth=verifiedFallback\n                    ?VERIFIED_FALLBACK_SEARCH_HALF_WIDTH_HZ:DISCOVERY_SEARCH_HALF_WIDTH_HZ;\n''','verified fallback search width')
s=replace_once(s,
'''                    if(directFeedbackLearning){\n                        double controlled=l.controller.output().frequencyHz();\n                        l.controller.refreshSecondaryPath(SecondaryPathFrequencyResponse.at(\n                                calibratedSecondaryPathFir,calibratedDelaySamples,calibratedSampleRateHz,controlled));\n                    }\n''',
'''                    if(verifiedFallback){\n                        double controlled=l.controller.output().frequencyHz();\n                        l.controller.refreshSecondaryPath(calibratedPathAt(controlled));\n                    }\n''','verified fallback path refresh')

s=replace_once(s,
'''    private boolean hasActiveRoomCancellationController(){\n''',
'''    private boolean hasActiveFallbackCancellationController(){\n''','rename active fallback helper')

s=replace_once(s,
'''    private int fallbackLaneLimit(){\n        if(directFeedbackLearning)return ROOM_MAX_DISCOVERED_LANES;\n        return broadbandEnabled?MAX_DISCOVERED_LANES_BROADBAND:MAX_DISCOVERED_LANES_NARROWBAND_ONLY;\n    }\n''',
'''    private int fallbackLaneLimit(){\n        if(directFeedbackLearning)return ROOM_MAX_DISCOVERED_LANES;\n        if(usesVerifiedFallbackSafety())return VERIFIED_VEHICLE_FALLBACK_MAX_DISCOVERED_LANES;\n        return broadbandEnabled?MAX_DISCOVERED_LANES_BROADBAND:MAX_DISCOVERED_LANES_NARROWBAND_ONLY;\n    }\n''','verified fallback lane limit')

s=replace_once(s,
'''                double collisionRadius=directFeedbackLearning\n                        ?ROOM_OWNERSHIP_RADIUS_HZ:DISCOVERY_COLLISION_RADIUS_HZ;\n''',
'''                double collisionRadius=directFeedbackLearning?ROOM_OWNERSHIP_RADIUS_HZ\n                        :usesVerifiedFallbackSafety()?VERIFIED_VEHICLE_OWNERSHIP_RADIUS_HZ\n                        :DISCOVERY_COLLISION_RADIUS_HZ;\n''','verified fallback collision radius')

# Telemetry lanes: prefer measured route calibration and never fall back to blind probes when
# calibration is available, while retaining vehicle timing/adaptation behaviour.
s=replace_once(s,
'''    private void startTelemetryController(Lane lane,long now){\n        lane.controller.setDirectErrorLearning(directFeedbackLearning);\n        double source=telemetry==null?Double.NaN:telemetry.sourceValue(lane.model);\n        VehicleCancellationRecipe recipe=recipeBook.find(routeKey,lane.model.id(),\n                lane.model.detectedNumberType(),source,lane.currentFrequencyHz);\n        if(recipe==null)lane.controller.startTracking(now,perLaneLimit(),lane.currentFrequencyHz,lane.label,true);\n        else lane.controller.startTrackingWithSecondaryPath(now,perLaneLimit(),lane.currentFrequencyHz,\n                lane.label,true,recipe.secondaryPath());\n        lane.idleSinceMs=0;lane.successUpdates=0;\n    }\n''',
'''    private void startTelemetryController(Lane lane,long now){\n        lane.controller.setDirectErrorLearning(directFeedbackLearning);\n        lane.controller.setBlindProbesAllowed(!hasCalibratedSecondaryPath());\n        double source=telemetry==null?Double.NaN:telemetry.sourceValue(lane.model);\n        VehicleCancellationRecipe recipe=recipeBook.find(routeKey,lane.model.id(),\n                lane.model.detectedNumberType(),source,lane.currentFrequencyHz);\n        Complex seededPath=recipe==null?calibratedPathAt(lane.currentFrequencyHz):recipe.secondaryPath();\n        if(seededPath.magnitude()>=ROOM_MIN_CALIBRATED_PATH_MAGNITUDE)\n            lane.controller.startTrackingWithSecondaryPath(now,perLaneLimit(),lane.currentFrequencyHz,\n                    lane.label,true,seededPath);\n        else lane.controller.startTracking(now,perLaneLimit(),lane.currentFrequencyHz,lane.label,true);\n        lane.idleSinceMs=0;lane.successUpdates=0;\n    }\n''','telemetry calibrated seed')

s=replace_once(s,
'''    private void startDiscoveredController(DiscoveredLane lane,long now){\n        lane.controller.setDirectErrorLearning(directFeedbackLearning);\n        VehicleCancellationRecipe recipe=recipeBook.find(routeKey,"discovered",\n                MechanicalFrequency.SourceType.FIXED,lane.anchorFrequencyHz,lane.currentFrequencyHz);\n        Complex seededPath=recipe==null?Complex.ZERO:recipe.secondaryPath();\n        if(directFeedbackLearning&&seededPath.magnitude()<ROOM_MIN_CALIBRATED_PATH_MAGNITUDE)\n            seededPath=SecondaryPathFrequencyResponse.at(calibratedSecondaryPathFir,calibratedDelaySamples,\n                    calibratedSampleRateHz,lane.currentFrequencyHz);\n        if(seededPath.magnitude()>=ROOM_MIN_CALIBRATED_PATH_MAGNITUDE){\n            lane.controller.startTrackingWithSecondaryPath(now,perLaneLimit(),lane.currentFrequencyHz,\n                    lane.label,false,seededPath);\n        }else if(directFeedbackLearning){\n            lane.controller.stop();\n            lane.auditRejected=true;\n            lane.auditRejectedMs=now;\n        }else{\n            lane.controller.startTracking(now,perLaneLimit(),lane.currentFrequencyHz,lane.label,false);\n        }\n        lane.idleSinceMs=0;lane.successUpdates=0;\n    }\n''',
'''    private void startDiscoveredController(DiscoveredLane lane,long now){\n        boolean verifiedFallback=usesVerifiedFallbackSafety();\n        // Microphone-only fallback has no external frequency reference. Whenever a calibrated\n        // speaker->microphone path exists, use Room-grade physical A/B verification and forbid\n        // blind path-identification tones for P38/E46 as well as Room.\n        lane.controller.setDirectErrorLearning(verifiedFallback);\n        lane.controller.setBlindProbesAllowed(!verifiedFallback);\n        VehicleCancellationRecipe recipe=recipeBook.find(routeKey,"discovered",\n                MechanicalFrequency.SourceType.FIXED,lane.anchorFrequencyHz,lane.currentFrequencyHz);\n        Complex seededPath=recipe==null?Complex.ZERO:recipe.secondaryPath();\n        if(verifiedFallback&&seededPath.magnitude()<ROOM_MIN_CALIBRATED_PATH_MAGNITUDE)\n            seededPath=calibratedPathAt(lane.currentFrequencyHz);\n        if(seededPath.magnitude()>=ROOM_MIN_CALIBRATED_PATH_MAGNITUDE){\n            lane.controller.startTrackingWithSecondaryPath(now,perLaneLimit(),lane.currentFrequencyHz,\n                    lane.label,false,seededPath);\n        }else if(verifiedFallback){\n            lane.controller.stop();\n            lane.auditRejected=true;\n            lane.auditRejectedMs=now;\n        }else{\n            lane.controller.startTracking(now,perLaneLimit(),lane.currentFrequencyHz,lane.label,false);\n        }\n        lane.idleSinceMs=0;lane.successUpdates=0;\n    }\n''','discovered calibrated safe controller')

s=replace_once(s,
'''    private void maybeCaptureRecipe(DiscoveredLane lane,long now){\n        if(directFeedbackLearning&&!lane.controller.hasPassedActiveVerification()){lane.successUpdates=0;return;}\n''',
'''    private void maybeCaptureRecipe(DiscoveredLane lane,long now){\n        if(usesVerifiedFallbackSafety()&&!lane.controller.hasPassedActiveVerification()){lane.successUpdates=0;return;}\n''','verified fallback recipe capture')

s=replace_once(s,
'''    private boolean nearOwnedFrequency(double hz){\n        double radius=directFeedbackLearning?ROOM_OWNERSHIP_RADIUS_HZ:DISCOVERY_DUPLICATE_RADIUS_HZ;\n''',
'''    private boolean nearOwnedFrequency(double hz){\n        double radius=directFeedbackLearning?ROOM_OWNERSHIP_RADIUS_HZ\n                :usesVerifiedFallbackSafety()?VERIFIED_VEHICLE_OWNERSHIP_RADIUS_HZ:DISCOVERY_DUPLICATE_RADIUS_HZ;\n''','verified fallback ownership radius')

# Status communicates the safer fallback architecture in vehicle profiles too.
s=replace_once(s,
'''                    :String.format(Locale.US,"No cancellable GPS/OBD lane · auto-discovering stable 8–200 Hz lines · %d found · %d cancelling · %d monitor-only%s",discovered.size(),cancelling,monitorOnly,telem.isEmpty()?"":"\\n"+telem);\n''',
'''                    :usesVerifiedFallbackSafety()\n                    ?String.format(Locale.US,"No cancellable GPS/OBD lane · calibrated-path dominant fallback · no blind probes · %d found · %d cancelling · %d monitor-only%s",discovered.size(),cancelling,monitorOnly,telem.isEmpty()?"":"\\n"+telem)\n                    :String.format(Locale.US,"No cancellable GPS/OBD lane · auto-discovering stable 8–200 Hz lines · %d found · %d cancelling · %d monitor-only%s",discovered.size(),cancelling,monitorOnly,telem.isEmpty()?"":"\\n"+telem);\n''','vehicle fallback status')
p.write_text(s)

# AudioEngine: pass the measured route FIR to Room/P38/E46 alike and invalidate old blind-probe recipes.
p=root/'anclab/src/main/java/com/p38/anclab/audio/AudioEngine.java'
s=p.read_text()
s=s.replace('/** Measure selected output -> selected microphone path for Headphones, P38 or E46. */',
            '/** Measure selected output -> selected microphone path for Headphones, Room, P38 or E46. */')
s=replace_once(s,
'''                        vehicleRecipeRouteKey(),profiles.loadCancellationRecipes(profileId),room,\n                        room?calibration.secondaryPath:null,room?calibration.delaySamples:0,calibration.sampleRateHz);\n''',
'''                        vehicleRecipeRouteKey(),profiles.loadCancellationRecipes(profileId),room,\n                        calibration.secondaryPath,calibration.delaySamples,calibration.sampleRateHz);\n''','pass calibrated path to all vehicle profiles')
s=replace_once(s,
'''        String key=activeProfile+"|in="+inputDeviceId+"|out="+outputDeviceId+"|cal="+revision;\n        return ProfileStore.PROFILE_ROOM.equals(activeProfile)?key+"|roomPath=cal-v1":key;\n''',
'''        String key=activeProfile+"|in="+inputDeviceId+"|out="+outputDeviceId+"|cal="+revision;\n        // v2 invalidates recipes learned by the older blind-probe path in P38/E46 as well as Room.\n        return key+"|narrowPath=cal-v2";\n''','invalidate old narrowband recipes')
p.write_text(s)

# Version bump.
p=root/'anclab/build.gradle.kts'
s=p.read_text()
s=replace_once(s,
'''        versionCode = 24\n        versionName = "0.6.6-room-dominant-tracking-dev"\n''',
'''        versionCode = 25\n        versionName = "0.6.7-shared-calibrated-safety-dev"\n''','version bump')
s=s.replace('Room ANC v0.6.6 prioritises dominant modes, tolerates Room-frequency wander, and refreshes the calibrated path while tracking.',
            'ANC v0.6.7 shares calibrated-path, no-blind-probe fallback safety across Room, P38 and E46 while retaining telemetry-specific vehicle tracking.')
p.write_text(s)

# Regression tests: a non-Room controller can now forbid blind probes, and calibrated vehicle
# fallback may safely share the wider persistence radius without changing the strict detector default.
p=root/'anclab/src/test/java/com/p38/anclab/dsp/SharedCalibratedSafetyTest.java'
p.write_text('''package com.p38.anclab.dsp;\n\nimport org.junit.Test;\nimport java.util.List;\nimport static org.junit.Assert.assertEquals;\nimport static org.junit.Assert.assertFalse;\nimport static org.junit.Assert.assertTrue;\n\npublic class SharedCalibratedSafetyTest {\n    private static SpectrumSnapshot snapshot(double magnitude) {\n        Complex c=new Complex(magnitude,0.0);\n        return new SpectrumSnapshot(68.0,c.magnitude(),-35,12,68.0,c,-35,-30,2.0);\n    }\n\n    private static SpectrumAnalyzer.DetectedTone tone(double hz,double db){\n        double amplitude=Math.pow(10.0,db/20.0);\n        return new SpectrumAnalyzer.DetectedTone(hz,amplitude,db,db-14.0,14.0);\n    }\n\n    @Test public void vehicleControllerCanForbidBlindProbeWithoutRoomMode(){\n        AutoController c=new AutoController();\n        c.setDirectErrorLearning(false);\n        c.setBlindProbesAllowed(false);\n        c.startTracking(1000,0.02,68.0,"E46 fallback",false);\n        c.update(snapshot(1.0),1800);\n        assertEquals("IDLE",c.stageName());\n        assertTrue(c.activeVerificationFailed());\n        assertEquals(0.0,c.output().gain(),1e-12);\n    }\n\n    @Test public void vehicleControllerRejectsBadCalibratedSeedWithoutBlindReprobe(){\n        AutoController c=new AutoController();\n        c.setDirectErrorLearning(false);\n        c.setBlindProbesAllowed(false);\n        c.startTrackingWithSecondaryPath(1000,0.02,68.0,"E46 telemetry",true,new Complex(1.0,0.0));\n        c.update(snapshot(1.0),1800);\n        c.update(snapshot(1.20),2600);\n        assertEquals("IDLE",c.stageName());\n        assertTrue(c.activeVerificationFailed());\n        assertEquals(0.0,c.output().gain(),1e-12);\n    }\n\n    @Test public void calibratedFallbackRadiusKeepsModerateVehicleToneWanderPersistent(){\n        BroadbandDetector detector=new BroadbandDetector();\n        detector.update(List.of(tone(131.4,-60)),0,2.5);\n        detector.update(List.of(tone(133.1,-60)),550,2.5);\n        List<BroadbandDetector.Candidate> ready=detector.update(List.of(tone(132.2,-60)),1100,2.5);\n        assertFalse(ready.isEmpty());\n        assertTrue(ready.get(0).frequencyHz()>130.0&&ready.get(0).frequencyHz()<134.5);\n    }\n}\n''')
