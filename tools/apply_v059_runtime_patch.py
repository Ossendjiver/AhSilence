from pathlib import Path


def replace(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected block not found in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")

# Vehicle fallback capacity/admission policy.
p = "anclab/src/main/java/com/p38/anclab/dsp/VehicleNarrowbandBank.java"
replace(p, "import java.util.ArrayList;\nimport java.util.Iterator;", "import java.util.ArrayList;\nimport java.util.Comparator;\nimport java.util.Iterator;")
replace(p, "    private static final int MAX_DISCOVERED_LANES=6;\n", "    private static final int MAX_DISCOVERED_LANES_BROADBAND=6;\n    private static final int MAX_DISCOVERED_LANES_NARROWBAND_ONLY=10;\n    private static final double DISCOVERY_ADMISSION_FLOOR_DBFS=-62.0;\n    private static final double DISCOVERY_REPLACEMENT_MARGIN_DB=6.0;\n")
replace(p, "    private boolean fallbackMode=false;\n", "    private boolean fallbackMode=false;\n    private boolean broadbandEnabled=false;\n")
replace(p,
"    public synchronized void setUserOutputScale(float scale){userScale=clamp(scale,0f,1f);redistributeLimits();}\n",
"    public synchronized void setUserOutputScale(float scale){userScale=clamp(scale,0f,1f);redistributeLimits();}\n    public synchronized void setBroadbandEnabled(boolean enabled){\n        broadbandEnabled=enabled;\n        if(enabled)trimExcessInactiveDiscovered(MAX_DISCOVERED_LANES_BROADBAND);\n        redistributeLimits();\n        publishLaneRegistry();\n    }\n")
old = '''            List<BroadbandDetector.Candidate> ready=discoveryDetector.update(peaks,now);\n            for(BroadbandDetector.Candidate candidate:ready){\n                if(discovered.size()>=MAX_DISCOVERED_LANES)break;\n                if(nearOwnedFrequency(candidate.frequencyHz()))continue;\n                DiscoveredLane lane=new DiscoveredLane(candidate.id(),candidate.frequencyHz(),now);\n                lane.referenceEpoch=totalAnalysisSamples;lane.referencePhase=lane.oscillator.phase;\n                lane.tracker.reset(lane.currentFrequencyHz,now);\n                lane.cancellable=FrequencyLanePolicy.cancellable(lane.currentFrequencyHz,\n                        cancellationMinimumHz,cancellationMaximumHz);\n                discovered.add(lane);\n                redistributeLimits();\n                if(lane.cancellable)startDiscoveredController(lane,now);else lane.controller.stop();\n                moved=true;changed=true;\n            }\n'''
new = '''            List<BroadbandDetector.Candidate> ready=new ArrayList<>(discoveryDetector.update(peaks,now));\n            ready.sort(Comparator.comparingDouble(BroadbandDetector.Candidate::dbFs).reversed());\n            for(BroadbandDetector.Candidate candidate:ready){\n                if(candidate.dbFs()<DISCOVERY_ADMISSION_FLOOR_DBFS)continue;\n                if(nearOwnedFrequency(candidate.frequencyHz()))continue;\n                DiscoveredLane replace=null;\n                if(discovered.size()>=fallbackLaneLimit())replace=findReplaceableLane(candidate,now);\n                if(discovered.size()>=fallbackLaneLimit()&&replace==null)continue;\n                if(replace!=null){replace.controller.stop();discovered.remove(replace);}\n                DiscoveredLane lane=new DiscoveredLane(candidate.id(),candidate.frequencyHz(),now);\n                lane.lastPeakDbFs=candidate.dbFs();\n                lane.referenceEpoch=totalAnalysisSamples;lane.referencePhase=lane.oscillator.phase;\n                lane.tracker.reset(lane.currentFrequencyHz,now);\n                lane.cancellable=FrequencyLanePolicy.cancellable(lane.currentFrequencyHz,\n                        cancellationMinimumHz,cancellationMaximumHz);\n                discovered.add(lane);\n                redistributeLimits();\n                if(lane.cancellable)startDiscoveredController(lane,now);else lane.controller.stop();\n                moved=true;changed=true;\n            }\n'''
replace(p, old, new)
replace(p, "                l.lastStrongMs=now;\n", "                l.lastStrongMs=now;l.lastPeakDbFs=search.peakDbFs();\n")
marker = '''    /** Keep the older lane when independent admissions converge onto one physical tone. */\n'''
helpers = '''    private int fallbackLaneLimit(){\n        return broadbandEnabled?MAX_DISCOVERED_LANES_BROADBAND:MAX_DISCOVERED_LANES_NARROWBAND_ONLY;\n    }\n\n    private DiscoveredLane findReplaceableLane(BroadbandDetector.Candidate candidate,long now){\n        DiscoveredLane weakest=null;\n        for(DiscoveredLane lane:discovered){\n            if(!"IDLE".equals(lane.controller.stageName())||lane.controller.output().gain()>1e-5)continue;\n            if(lane.idleSinceMs==0||now-lane.idleSinceMs<FrequencyLanePolicy.CONTROLLER_RETRY_DELAY_MS)continue;\n            if(candidate.dbFs()<lane.lastPeakDbFs+DISCOVERY_REPLACEMENT_MARGIN_DB)continue;\n            if(weakest==null||lane.lastPeakDbFs<weakest.lastPeakDbFs)weakest=lane;\n        }\n        return weakest;\n    }\n\n    private void trimExcessInactiveDiscovered(int limit){\n        if(discovered.size()<=limit)return;\n        for(int i=discovered.size()-1;i>=0&&discovered.size()>limit;i--){\n            DiscoveredLane lane=discovered.get(i);\n            if(lane.controller.output().gain()>1e-5||!"IDLE".equals(lane.controller.stageName()))continue;\n            lane.controller.stop();discovered.remove(i);frequencyRevision++;\n        }\n    }\n\n'''
replace(p, marker, helpers+marker)
replace(p,
"        final String id,label;final double anchorFrequencyHz;final AutoController controller=new AutoController();final AdaptiveFrequencyTracker tracker=new AdaptiveFrequencyTracker();final Oscillator oscillator=new Oscillator();long referenceEpoch;double referencePhase;double currentFrequencyHz;long lastStrongMs,lastRecipeMs,idleSinceMs;boolean cancellable;int successUpdates;\n",
"        final String id,label;final double anchorFrequencyHz;final AutoController controller=new AutoController();final AdaptiveFrequencyTracker tracker=new AdaptiveFrequencyTracker();final Oscillator oscillator=new Oscillator();long referenceEpoch;double referencePhase;double currentFrequencyHz,lastPeakDbFs=-120.0;long lastStrongMs,lastRecipeMs,idleSinceMs;boolean cancellable;int successUpdates;\n")

# Feed raw measured microphone into broadband controller; exclusions now happen after disturbance reconstruction.
p = "anclab/src/main/java/com/p38/anclab/audio/AudioEngine.java"
replace(p,
'''    public synchronized void setVehicleBroadbandEnabled(boolean enabled){\n        vehicleBroadbandEnabled=enabled;\n        if(mode!=Mode.VEHICLE||!running.get())return;\n        if(!enabled){vehicleFx=null;safetyStatus="Speculative broadband OFF · telemetry narrowband lanes continue";}\n        else if(calibration!=null){vehicleFx=new FeedbackFxNlms(calibration.secondaryPath,calibration.delaySamples,128,calibration.safeOutputCeiling);configureVehicleFx(vehicleFx);safetyStatus="Speculative broadband ON · live predictable lane bands excluded";}\n    }\n''',
'''    public synchronized void setVehicleBroadbandEnabled(boolean enabled){\n        vehicleBroadbandEnabled=enabled;\n        VehicleNarrowbandBank bank=vehicleNarrowband;if(bank!=null)bank.setBroadbandEnabled(enabled);\n        if(mode!=Mode.VEHICLE||!running.get())return;\n        if(!enabled){vehicleFx=null;safetyStatus="Speculative broadband OFF · telemetry narrowband lanes continue";}\n        else if(calibration!=null){vehicleFx=new FeedbackFxNlms(calibration.secondaryPath,calibration.delaySamples,128,calibration.safeOutputCeiling);configureVehicleFx(vehicleFx);if(bank!=null)vehicleFx.setExcludedFrequencies(bank.frequenciesHz());safetyStatus="Speculative broadband ON · live predictable lane bands excluded";}\n    }\n''')
replace(p,
'''                vehicleExcluder=new PredictableFrequencyExcluder(SAMPLE_RATE,vehicleNarrowband.frequenciesHz());\n                lastVehicleFrequencyRevision=vehicleNarrowband.frequencyRevision();lastVehicleExcluderUpdateMs=System.currentTimeMillis();\n                if(broadbandEnabled){vehicleFx=new FeedbackFxNlms(calibration.secondaryPath,calibration.delaySamples,128,calibration.safeOutputCeiling);configureVehicleFx(vehicleFx);}else vehicleFx=null;\n''',
'''                vehicleNarrowband.setBroadbandEnabled(broadbandEnabled);\n                lastVehicleFrequencyRevision=vehicleNarrowband.frequencyRevision();lastVehicleExcluderUpdateMs=System.currentTimeMillis();\n                if(broadbandEnabled){vehicleFx=new FeedbackFxNlms(calibration.secondaryPath,calibration.delaySamples,128,calibration.safeOutputCeiling);configureVehicleFx(vehicleFx);vehicleFx.setExcludedFrequencies(vehicleNarrowband.frequenciesHz());}else vehicleFx=null;\n''')
replace(p,
'''                    if(vehicleBroadbandEnabled&&vehicleFx!=null){float broadIn=vehicleExcluder==null?in[i]:vehicleExcluder.process(in[i]);vehicleFx.process(broadIn);broadModel=vehicleFx.diagnosticModelDrive();ref=vehicleFx.diagnosticReference();cancel=vehicleFx.diagnosticPredictedCancellation();residual=vehicleFx.diagnosticMeasuredResidual();}\n''',
'''                    if(vehicleBroadbandEnabled&&vehicleFx!=null){vehicleFx.process(in[i]);broadModel=vehicleFx.diagnosticModelDrive();ref=vehicleFx.diagnosticReference();cancel=vehicleFx.diagnosticPredictedCancellation();residual=vehicleFx.diagnosticMeasuredResidual();}\n''')
replace(p,
'''        if(vehicleNarrowband==null||vehicleExcluder==null)return;long rev=vehicleNarrowband.frequencyRevision();long now=System.currentTimeMillis();\n        if(rev==lastVehicleFrequencyRevision||now-lastVehicleExcluderUpdateMs<750)return;\n        vehicleExcluder.setFrequencies(vehicleNarrowband.frequenciesHz());lastVehicleFrequencyRevision=rev;lastVehicleExcluderUpdateMs=now;\n''',
'''        if(vehicleNarrowband==null||vehicleFx==null)return;long rev=vehicleNarrowband.frequencyRevision();long now=System.currentTimeMillis();\n        if(rev==lastVehicleFrequencyRevision||now-lastVehicleExcluderUpdateMs<750)return;\n        vehicleFx.setExcludedFrequencies(vehicleNarrowband.frequenciesHz());lastVehicleFrequencyRevision=rev;lastVehicleExcluderUpdateMs=now;\n''')

# Match the headphone virtual microphone / filtered-X path to the 15-600 Hz observation path.
p = "anclab/src/main/java/com/p38/anclab/dsp/HeadphoneFeedforwardFxNlms.java"
replace(p,
"    private final HeadphoneBandLimiter outputLowPass=new HeadphoneBandLimiter(SAMPLE_RATE,false);\n    private final HeadphoneBandLimiter filteredXPathLowPass=new HeadphoneBandLimiter(SAMPLE_RATE,false);\n",
"    private final HeadphoneBandLimiter outputLowPass=new HeadphoneBandLimiter(SAMPLE_RATE,false);\n    private final HeadphoneBandLimiter secondaryObservationBand=new HeadphoneBandLimiter(SAMPLE_RATE,true);\n    private final HeadphoneBandLimiter filteredXPathLowPass=new HeadphoneBandLimiter(SAMPLE_RATE,false);\n    private final HeadphoneBandLimiter filteredXObservationBand=new HeadphoneBandLimiter(SAMPLE_RATE,true);\n")
replace(p,
"        seedControllerFromSecondaryPath();referenceBand.reset();outputLowPass.reset();filteredXPathLowPass.reset();\n",
"        seedControllerFromSecondaryPath();referenceBand.reset();outputLowPass.reset();secondaryObservationBand.reset();filteredXPathLowPass.reset();filteredXObservationBand.reset();\n")
replace(p,
"        float predictedCancellation=convolveSecondary(driveHistory,drivePos);\n        float predictedResidual=predictedFuture+predictedCancellation;\n",
"        float predictedCancellation=secondaryObservationBand.process(convolveSecondary(driveHistory,drivePos));\n        float predictedResidual=predictedFuture+predictedCancellation;\n")
replace(p,
"        float xf=convolveSecondary(predictorPathHistory,pathPos);filteredPredictedHistory[filteredPos]=xf;\n",
"        float xf=filteredXObservationBand.process(convolveSecondary(predictorPathHistory,pathPos));filteredPredictedHistory[filteredPos]=xf;\n")

# Version and artifact identity.
replace("anclab/build.gradle.kts", '        versionCode = 14\n        versionName = "0.5.8.1-rebuild"\n', '        versionCode = 15\n        versionName = "0.5.9-rebuild"\n')
replace(".github/workflows/build-anclab.yml", "      - feature/p38-dryrun-v058\n", "      - feature/p38-dryrun-v058\n      - feature/anc-lab-v059\n")
replace(".github/workflows/build-anclab.yml", "          name: ANC-Lab-v0.5.8-rebuild-debug\n", "          name: ANC-Lab-v0.5.9-rebuild-debug\n")

print("v0.5.9 runtime patch applied")
