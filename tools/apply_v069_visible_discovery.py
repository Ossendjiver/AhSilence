from pathlib import Path


def replace_once(text, old, new, label):
    count=text.count(old)
    if count!=1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old,new,1)

root=Path(__file__).resolve().parents[1]

# ---------------------------------------------------------------------------
# VehicleLaneRegistry: observations are not the same thing as cancellation lanes.
# Keep a separate persistent-tone snapshot so the UI can show what the detector
# actually hears even when telemetry owns the frequency or admission is paused.
# ---------------------------------------------------------------------------
p=root/'anclab/src/main/java/com/p38/anclab/dsp/VehicleLaneRegistry.java'
p.write_text('''package com.p38.anclab.dsp;\n\nimport java.util.List;\nimport java.util.Locale;\n\n/** Process-wide read-only snapshot of narrowband observations and control lanes for UI/media surfaces. */\npublic final class VehicleLaneRegistry {\n    public record Lane(String id,String label,double frequencyHz,double gain,double phaseDegrees,\n                       String stage,String status,double improvementDb,boolean discovered,\n                       boolean monitorOnly) { }\n    public record ObservedTone(String id,double frequencyHz,double dbFs,double prominenceDb,\n                               int confirmations,String relation) { }\n\n    private static volatile List<Lane> lanes=List.of();\n    private static volatile List<ObservedTone> observed=List.of();\n    private static volatile long updatedMs=0L;\n\n    private VehicleLaneRegistry() { }\n\n    public static void publish(List<Lane> value){publish(value,List.of());}\n    public static void publish(List<Lane> value,List<ObservedTone> tones){\n        lanes=value==null?List.of():List.copyOf(value);\n        observed=tones==null?List.of():List.copyOf(tones);\n        updatedMs=System.currentTimeMillis();\n    }\n    public static void clear(){publish(List.of(),List.of());}\n    public static List<Lane> lanes(){return lanes;}\n    public static List<ObservedTone> observed(){return observed;}\n    public static int monitoredCount(){return lanes.size();}\n    public static int observedCount(){return observed.size();}\n    public static int activeCount(){int n=0;for(Lane l:lanes)if(l.gain()>1e-5)n++;return n;}\n    public static long updatedMs(){return updatedMs;}\n\n    public static String summary(){\n        List<Lane> laneSnapshot=lanes;List<ObservedTone> toneSnapshot=observed;\n        StringBuilder b=new StringBuilder("Observed persistent frequencies:");\n        if(toneSnapshot.isEmpty())b.append(" —");\n        else{int j=0;for(ObservedTone t:toneSnapshot){\n            b.append(j++==0?'\\n':'\\n').append(String.format(Locale.US,\n                    "%d · %.2f Hz · %.1f dBFS · %.1f dB prominence · %s",\n                    j,t.frequencyHz(),t.dbFs(),t.prominenceDb(),t.relation()));\n        }}\n        b.append("\\nCancellation lanes:");\n        if(laneSnapshot.isEmpty())return b.append(" —").toString();\n        int i=0;for(Lane l:laneSnapshot){\n            b.append('\\n');\n            String prefix=l.discovered()?"Discovered ":"Mechanical ";\n            String state=l.monitorOnly()?"monitor only":l.gain()>1e-5?"running":prettyStage(l.stage());\n            b.append(prefix).append(++i).append(" · ")\n                    .append(String.format(Locale.US,"%.2f Hz · %.2f%% · %s",l.frequencyHz(),l.gain()*100.0,state));\n            if(Double.isFinite(l.improvementDb())&&l.gain()>1e-5)\n                b.append(String.format(Locale.US," · %.1f dB",l.improvementDb()));\n        }\n        return b.toString();\n    }\n\n    private static String prettyStage(String stage){\n        if(stage==null||stage.isEmpty()||"IDLE".equals(stage))return "idle";\n        if(stage.startsWith("PROBE"))return "path probe";\n        if(stage.startsWith("REFINE"))return "micro-refining path";\n        if(stage.startsWith("VERIFY"))return "verifying";\n        if(stage.startsWith("SEEK"))return "tracking";\n        if("FOLLOW_VERIFY".equals(stage))return "following";\n        if("BASELINE".equals(stage))return "baseline";\n        if("RUNNING".equals(stage))return "running";\n        return stage.toLowerCase(Locale.US).replace('_',' ');\n    }\n}\n''')

# ---------------------------------------------------------------------------
# VehicleTelemetryRuntime: a stationary P38 with no OBD does NOT provide evidence
# that the engine is running at 714 rpm.  Previously this fabricated an entire set
# of "controllable" engine orders and prevented microphone fallback from running.
# ---------------------------------------------------------------------------
p=root/'anclab/src/main/java/com/p38/anclab/telemetry/VehicleTelemetryRuntime.java'
s=p.read_text()
s=replace_once(s,
'''        if(speedKmh<=3.0)return P38_IDLE_RPM;\n        if(speedKmh>=55.0&&speedKmh<=125.0)return Math.max(P38_IDLE_RPM,speedKmh*P38_HIGHWAY_RPM_PER_KMH);\n        return Double.NaN; // lower gears cannot be inferred safely from speed alone\n''',
'''        // At rest, GPS speed alone cannot tell us whether the engine is running, much less its\n        // exact idle speed.  Treating 0 km/h as a synthetic 714-rpm telemetry source used to create\n        // eight apparently "controllable" P38 engine lanes and globally suppress microphone\n        // discovery.  Let the microphone discover stationary/idle tones when real OBD RPM is absent.\n        if(speedKmh<=3.0)return Double.NaN;\n        if(speedKmh>=55.0&&speedKmh<=125.0)return Math.max(P38_IDLE_RPM,speedKmh*P38_HIGHWAY_RPM_PER_KMH);\n        return Double.NaN; // lower gears cannot be inferred safely from speed alone\n''','stationary P38 must not fabricate RPM telemetry')
p.write_text(s)

# ---------------------------------------------------------------------------
# VehicleNarrowbandBank: (1) observe persistent microphone frequencies all the
# time for UI diagnostics; (2) let microphone discovery coexist with telemetry
# instead of using a global controllable==0 switch; (3) only let an acoustically
# locked/verified telemetry lane claim a nearby microphone candidate.
# ---------------------------------------------------------------------------
p=root/'anclab/src/main/java/com/p38/anclab/dsp/VehicleNarrowbandBank.java'
s=p.read_text()
s=replace_once(s,
'''    private final BroadbandDetector discoveryDetector=new BroadbandDetector();\n    private final List<DiscoveredLane> discovered=new ArrayList<>();\n''',
'''    private final BroadbandDetector discoveryDetector=new BroadbandDetector();\n    private final BroadbandDetector observationDetector=new BroadbandDetector();\n    private List<BroadbandDetector.Candidate> observedCandidates=List.of();\n    private final List<DiscoveredLane> discovered=new ArrayList<>();\n''','observation detector fields')
s=replace_once(s,
'''    private int ringPos=0,ringCount=0,decimator=0,sinceAnalysis=0,discoveryAnalysisCounter=0;\n''',
'''    private int ringPos=0,ringCount=0,decimator=0,sinceAnalysis=0,discoveryAnalysisCounter=0,observationAnalysisCounter=0;\n''','observation counter')
s=replace_once(s,
'''        int available=0,controllable=0,cancelling=0,learning=0,monitorOnly=0;\n        boolean moved=false,availabilityChanged=false;\n\n        // Resolve telemetry ownership before updating controllers. A later model that converges on\n''',
'''        int available=0,controllable=0,cancelling=0,learning=0,monitorOnly=0;\n        boolean moved=false,availabilityChanged=false;\n\n        // Observation is deliberately independent of controller admission.  The user should always\n        // be able to see persistent microphone lines, including lines near telemetry models and while\n        // a cancellation controller is being verified.\n        updateObservedFrequencies(window,first,now);\n\n        // Resolve telemetry ownership before updating controllers. A later model that converges on\n''','always update observed frequencies')
s=replace_once(s,
'''            boolean valid=Double.isFinite(predicted)&&predicted>=8.0&&predicted<=200.0;\n            l.predictedFrequencyHz=predicted;l.available=valid;l.suppressed=false;\n''',
'''            boolean valid=Double.isFinite(predicted)&&predicted>=8.0&&predicted<=200.0;\n            l.predictedFrequencyHz=predicted;l.available=valid;l.suppressed=false;l.acousticLock=false;\n''','reset telemetry acoustic lock')
s=replace_once(s,
'''            boolean acousticLock=search.peakDbFs()>-78.0&&search.contrastDb()>2.2&&Math.abs(search.peakFrequencyHz()-predicted)<=SEARCH_HALF_WIDTH_HZ;\n            double refined=predicted;\n''',
'''            boolean acousticLock=search.peakDbFs()>-78.0&&search.contrastDb()>2.2&&Math.abs(search.peakFrequencyHz()-predicted)<=SEARCH_HALF_WIDTH_HZ;\n            l.acousticLock=acousticLock;\n            double refined=predicted;\n''','store telemetry acoustic lock')
s=replace_once(s,
'''        if(controllable==0){\n            if(!fallbackMode){fallbackMode=true;discoveryAnalysisCounter=DISCOVERY_SCAN_EVERY_ANALYSES;discoveryDetector.reset();}\n            DiscoveryResult r=analyzeFallbackDiscovery(window,first,now);\n            moved|=r.moved;cancelling+=r.cancelling;\n        }else if(fallbackMode||!discovered.isEmpty()){\n            fallbackMode=false;clearDiscovered(true);discoveryDetector.reset();moved=true;availabilityChanged=true;\n        }\n''',
'''        // Do not make microphone discovery mutually exclusive with telemetry.  Telemetry is a\n        // frequency prior, not proof that every important cabin line is one of the configured orders.\n        // Nearby acoustically locked telemetry lanes still own their tone (nearOwnedFrequency), but\n        // unrelated or badly-missed persistent lines may become safe fallback controllers.\n        if(!fallbackMode){fallbackMode=true;discoveryAnalysisCounter=DISCOVERY_SCAN_EVERY_ANALYSES;discoveryDetector.reset();}\n        DiscoveryResult r=analyzeFallbackDiscovery(window,first,now);\n        moved|=r.moved;cancelling+=r.cancelling;\n''','coexistent telemetry and fallback discovery')
s=replace_once(s,
'''            status=String.format(Locale.US,"Predictable narrowband · %d/%d telemetry · %d controllable · %d cancelling · %d monitor-only · %d learning%s",\n                    available,lanes.length,controllable,cancelling,monitorOnly,learning,telem.isEmpty()?"":"\\n"+telem);\n''',
'''            status=String.format(Locale.US,"Predictable narrowband + microphone observation · %d/%d telemetry · %d controllable · %d discovered fallback · %d cancelling · %d monitor-only · %d learning%s",\n                    available,lanes.length,controllable,discovered.size(),cancelling,monitorOnly,learning,telem.isEmpty()?"":"\\n"+telem);\n''','telemetry status includes discovery')

insert_anchor='''    private synchronized DiscoveryResult analyzeFallbackDiscovery(float[] window,long first,long now){\n'''
insert='''    private synchronized void updateObservedFrequencies(float[] window,long first,long now){\n        if(++observationAnalysisCounter<DISCOVERY_SCAN_EVERY_ANALYSES)return;\n        observationAnalysisCounter=0;\n        List<SpectrumAnalyzer.DetectedTone> peaks=SpectrumAnalyzer.findPeaks(window,first,ANALYSIS_RATE,\n                8.0,200.0,12,DISCOVERY_MIN_SEPARATION_HZ);\n        List<BroadbandDetector.Candidate> ready=new ArrayList<>(observationDetector.update(\n                peaks,now,VERIFIED_FALLBACK_MATCH_RADIUS_HZ));\n        ready.removeIf(c->c.dbFs()<DISCOVERY_ADMISSION_FLOOR_DBFS\n                ||c.prominenceDb()<DISCOVERY_MIN_PROMINENCE_DB);\n        ready.sort((a,b)->Double.compare(b.dbFs(),a.dbFs()));\n        if(ready.size()>8)ready=new ArrayList<>(ready.subList(0,8));\n        observedCandidates=List.copyOf(ready);\n    }\n\n    private synchronized DiscoveryResult analyzeFallbackDiscovery(float[] window,long first,long now){\n'''
s=replace_once(s,insert_anchor,insert,'insert always-on observation method')
s=replace_once(s,
'''    private boolean nearOwnedFrequency(double hz){\n        double radius=directFeedbackLearning?ROOM_OWNERSHIP_RADIUS_HZ\n                :usesVerifiedFallbackSafety()?VERIFIED_VEHICLE_OWNERSHIP_RADIUS_HZ:DISCOVERY_DUPLICATE_RADIUS_HZ;\n        for(Lane l:lanes)if(l.available&&Math.abs(l.controller.output().frequencyHz()-hz)<=radius)return true;\n        for(DiscoveredLane l:discovered)if(Math.abs(l.controller.output().frequencyHz()-hz)<=radius)return true;\n        return false;\n    }\n''',
'''    private boolean nearOwnedFrequency(double hz){\n        double radius=directFeedbackLearning?ROOM_OWNERSHIP_RADIUS_HZ\n                :usesVerifiedFallbackSafety()?VERIFIED_VEHICLE_OWNERSHIP_RADIUS_HZ:DISCOVERY_DUPLICATE_RADIUS_HZ;\n        for(Lane l:lanes){\n            boolean verifiedOwner=l.acousticLock\n                    ||(l.controller.hasPassedActiveVerification()&&l.controller.output().gain()>1e-5);\n            if(l.available&&verifiedOwner&&Math.abs(l.controller.output().frequencyHz()-hz)<=radius)return true;\n        }\n        for(DiscoveredLane l:discovered)if(Math.abs(l.controller.output().frequencyHz()-hz)<=radius)return true;\n        return false;\n    }\n\n    private String observedRelation(double hz){\n        double radius=directFeedbackLearning?ROOM_OWNERSHIP_RADIUS_HZ\n                :usesVerifiedFallbackSafety()?VERIFIED_VEHICLE_OWNERSHIP_RADIUS_HZ:DISCOVERY_DUPLICATE_RADIUS_HZ;\n        for(DiscoveredLane l:discovered)if(Math.abs(l.controller.output().frequencyHz()-hz)<=radius)return "discovery lane";\n        for(Lane l:lanes){\n            if(!l.available||Math.abs(l.controller.output().frequencyHz()-hz)>radius)continue;\n            if(l.acousticLock)return "telemetry-owned";\n            if(l.controller.hasPassedActiveVerification()&&l.controller.output().gain()>1e-5)return "verified telemetry";\n            return "near unverified telemetry";\n        }\n        return "unowned candidate";\n    }\n''','telemetry only owns acoustically supported candidates')
s=replace_once(s,
'''        VehicleLaneRegistry.publish(state);\n''',
'''        List<VehicleLaneRegistry.ObservedTone> tones=new ArrayList<>();\n        for(BroadbandDetector.Candidate c:observedCandidates){\n            tones.add(new VehicleLaneRegistry.ObservedTone(c.id(),c.frequencyHz(),c.dbFs(),\n                    c.prominenceDb(),c.confirmations(),observedRelation(c.frequencyHz())));\n        }\n        VehicleLaneRegistry.publish(state,tones);\n''','publish observations')
s=replace_once(s,
'''        final MechanicalFrequency model;final String label;final AutoController controller=new AutoController();final AdaptiveFrequencyTracker tracker=new AdaptiveFrequencyTracker();final Oscillator oscillator=new Oscillator();long referenceEpoch;double referencePhase;double currentFrequencyHz,predictedFrequencyHz;boolean available,cancellable,suppressed,controllerEnabled;int successUpdates;long lastRecipeMs,idleSinceMs;\n''',
'''        final MechanicalFrequency model;final String label;final AutoController controller=new AutoController();final AdaptiveFrequencyTracker tracker=new AdaptiveFrequencyTracker();final Oscillator oscillator=new Oscillator();long referenceEpoch;double referencePhase;double currentFrequencyHz,predictedFrequencyHz;boolean available,cancellable,suppressed,controllerEnabled,acousticLock;int successUpdates;long lastRecipeMs,idleSinceMs;\n''','lane acoustic lock field')
p.write_text(s)

# ---------------------------------------------------------------------------
# MainActivity: tell the user how many persistent frequencies are actually being
# observed, independently from how many controllers have been admitted.
# ---------------------------------------------------------------------------
p=root/'anclab/src/main/java/com/p38/anclab/MainActivity.java'
s=p.read_text()
s=replace_once(s,
'''laneCountText.setText(String.format(Locale.US,"%d lanes monitored · %d actively cancelling",VehicleLaneRegistry.monitoredCount(),VehicleLaneRegistry.activeCount()));laneListText.setText(VehicleLaneRegistry.summary());''',
'''laneCountText.setText(String.format(Locale.US,"%d control lanes · %d persistent tones observed · %d actively cancelling",VehicleLaneRegistry.monitoredCount(),VehicleLaneRegistry.observedCount(),VehicleLaneRegistry.activeCount()));laneListText.setText(VehicleLaneRegistry.summary());''','UI observation count')
p.write_text(s)

# ---------------------------------------------------------------------------
# AudioEngine: an AudioRecord that returns exact digital zero is not a quiet room;
# it is an invalid microphone stream. Stop explicitly instead of showing 0 tones
# forever and giving the impression that discovery is broken.
# ---------------------------------------------------------------------------
p=root/'anclab/src/main/java/com/p38/anclab/audio/AudioEngine.java'
s=p.read_text()
s=replace_once(s,
'''    private long lastVehicleFrequencyRevision=-1L,lastVehicleExcluderUpdateMs=0L;\n''',
'''    private long lastVehicleFrequencyRevision=-1L,lastVehicleExcluderUpdateMs=0L;\n    private int digitalSilenceFrames=0;\n''','digital silence field')
s=replace_once(s,
'''            mode=requested;activeProfile=profileId;vehicleBroadbandEnabled=broadbandEnabled;safetyStatus="";lastError="None";\n''',
'''            mode=requested;activeProfile=profileId;vehicleBroadbandEnabled=broadbandEnabled;safetyStatus="";lastError="None";digitalSilenceFrames=0;\n''','reset digital silence on start')
s=replace_once(s,
'''            int w=track.write(out,0,n,AudioTrack.WRITE_BLOCKING);if(w==AudioTrack.ERROR_DEAD_OBJECT){lastError="Audio output disconnected · ANC stopped";break;}if(w<0){lastError="AudioTrack write error "+w;break;}\n''',
'''            int w=track.write(out,0,n,AudioTrack.WRITE_BLOCKING);if(w==AudioTrack.ERROR_DEAD_OBJECT){lastError="Audio output disconnected · ANC stopped";break;}if(w<0){lastError="AudioTrack write error "+w;break;}\n            // A physical microphone has analogue/self noise. One full second of exactly zero-valued\n            // float samples means Android has handed us a silent/invalid capture stream, not that the\n            // environment simply contains no discoverable tone. Fail visibly instead of silently\n            // running an empty detector for the rest of the session.\n            if(inputEnergy<=1.0e-20){\n                digitalSilenceFrames+=n;\n                if(digitalSilenceFrames>=SAMPLE_RATE){\n                    lastError="Microphone input is digital silence · ANC stopped · reselect the input route and re-run calibration if it recurs";\n                    safetyStatus=lastError;running.set(false);\n                }\n            }else digitalSilenceFrames=0;\n''','digital silence watchdog')
s=replace_once(s,
'''routeGainCompensation=1f;lastVehicleFrequencyRevision=-1;}\n''',
'''routeGainCompensation=1f;lastVehicleFrequencyRevision=-1;digitalSilenceFrames=0;}\n''','reset digital silence on stop')
p.write_text(s)

# Version bump.
p=root/'anclab/build.gradle.kts'
s=p.read_text()
s=replace_once(s,
'''        versionCode = 26\n        versionName = "0.6.8-calibrated-micro-refinement-dev"\n''',
'''        versionCode = 27\n        versionName = "0.6.9-visible-coexistent-discovery-dev"\n''','version bump')
s=s.replace('// ANC v0.6.8 adds one bounded calibration-gated micro-refinement when the stored low-frequency path misses, shared across Room, P38 and E46.',
            '// ANC v0.6.9 separates persistent-frequency observation from controller admission and allows safe discovery to coexist with telemetry.')
p.write_text(s)

# Registry regression: detector observations remain visible even with no admitted cancellation lane.
p=root/'anclab/src/test/java/com/p38/anclab/dsp/VehicleLaneRegistryObservationTest.java'
p.write_text('''package com.p38.anclab.dsp;\n\nimport org.junit.Test;\nimport java.util.List;\nimport static org.junit.Assert.assertEquals;\nimport static org.junit.Assert.assertTrue;\n\npublic class VehicleLaneRegistryObservationTest {\n    @Test public void persistentObservationIsVisibleWithoutCancellationLane(){\n        VehicleLaneRegistry.publish(List.of(),List.of(new VehicleLaneRegistry.ObservedTone(\n                "broad-85.0",84.96,-50.0,38.0,8,"unowned candidate")));\n        assertEquals(0,VehicleLaneRegistry.monitoredCount());\n        assertEquals(1,VehicleLaneRegistry.observedCount());\n        assertEquals(0,VehicleLaneRegistry.activeCount());\n        assertTrue(VehicleLaneRegistry.summary().contains("84.96 Hz"));\n        assertTrue(VehicleLaneRegistry.summary().contains("unowned candidate"));\n        VehicleLaneRegistry.clear();\n    }\n}\n''')
