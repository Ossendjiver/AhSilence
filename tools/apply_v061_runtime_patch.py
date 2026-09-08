from pathlib import Path


def rep(path, old, new):
    p=Path(path); s=p.read_text()
    if old not in s:
        raise SystemExit(f'expected block not found in {path}: {old[:100]!r}')
    p.write_text(s.replace(old,new,1))

# AutoController is safe above 200 Hz for headphone-only callers; vehicle callers retain their own 8-200 bounds.
p='anclab/src/main/java/com/p38/anclab/dsp/AutoController.java'
rep(p,'this.frequencyHz = clamp(frequencyHz, 8.0, 200.0);','this.frequencyHz = clamp(frequencyHz, 8.0, 600.0);')
rep(p,'requestedHz = clamp(requestedHz, 8.0, 200.0);','requestedHz = clamp(requestedHz, 8.0, 600.0);')

# Feedforward predictor must not fight the dedicated tone bank.
p='anclab/src/main/java/com/p38/anclab/dsp/HeadphoneFeedforwardFxNlms.java'
rep(p,'private final PredictableFrequencyDiscovery predictableDiscovery=new PredictableFrequencyDiscovery(15.0,600.0);',
      'private final PredictableFrequencyDiscovery predictableDiscovery=new PredictableFrequencyDiscovery(15.0,600.0);\n    private final PredictableFrequencyExcluder ownedToneExcluder=new PredictableFrequencyExcluder(SAMPLE_RATE,new double[0]);')
rep(p,'public void setRouteGainCompensation(float v){routeGainCompensation=clamp(v,0f,4f);}',
      'public void setRouteGainCompensation(float v){routeGainCompensation=clamp(v,0f,4f);}\n    public void setExcludedFrequencies(double[] frequenciesHz){ownedToneExcluder.setFrequencies(frequenciesHz);}')
rep(p,'seedControllerFromSecondaryPath();referenceBand.reset();outputLowPass.reset();secondaryObservationBand.reset();filteredXPathLowPass.reset();filteredXObservationBand.reset();',
      'seedControllerFromSecondaryPath();referenceBand.reset();ownedToneExcluder.reset();outputLowPass.reset();secondaryObservationBand.reset();filteredXPathLowPass.reset();filteredXObservationBand.reset();')
rep(p,'float x=referenceBand.process(referenceMic);\n        predictableDiscovery.observe(x,System.currentTimeMillis());',
      'float observed=referenceBand.process(referenceMic);\n        predictableDiscovery.observe(observed);\n        float x=ownedToneExcluder.process(observed);')
rep(p,'public double[] discoveredPredictableFrequenciesHz(){return predictableDiscovery.frequenciesHz();}',
      'public double[] discoveredPredictableFrequenciesHz(){return predictableDiscovery.frequenciesHz();}\n    public String predictableFrequencySummary(){return predictableDiscovery.summary();}')

# Vehicle fallback must use the same relative-floor detector; absolute dBFS is only a sanity floor.
p='anclab/src/main/java/com/p38/anclab/dsp/VehicleNarrowbandBank.java'
rep(p,'private static final double DISCOVERY_ADMISSION_FLOOR_DBFS=-62.0;',
      'private static final double DISCOVERY_ADMISSION_FLOOR_DBFS=-110.0;\n    private static final double DISCOVERY_MIN_PROMINENCE_DB=8.0;')
rep(p,'ready.sort(Comparator.comparingDouble(BroadbandDetector.Candidate::dbFs).reversed());',
      'ready.sort(Comparator.comparingDouble(BroadbandDetector.Candidate::score).reversed());')
rep(p,'if(candidate.dbFs()<DISCOVERY_ADMISSION_FLOOR_DBFS)continue;',
      'if(candidate.dbFs()<DISCOVERY_ADMISSION_FLOOR_DBFS||candidate.prominenceDb()<DISCOVERY_MIN_PROMINENCE_DB)continue;')
rep(p,'boolean lock=search.peakDbFs()>-76.0&&search.contrastDb()>2.0&&Math.abs(search.peakFrequencyHz()-centre)<=DISCOVERY_SEARCH_HALF_WIDTH_HZ;',
      'boolean lock=search.peakDbFs()>-112.0&&search.contrastDb()>1.5&&Math.abs(search.peakFrequencyHz()-centre)<=DISCOVERY_SEARCH_HALF_WIDTH_HZ;')

# Wire headphone tone ownership + stationary profiler into the runtime and richer recording telemetry.
p='anclab/src/main/java/com/p38/anclab/audio/AudioEngine.java'
rep(p,'import com.p38.anclab.dsp.HeadphoneFeedforwardFxNlms;',
      'import com.p38.anclab.dsp.HeadphoneFeedforwardFxNlms;\nimport com.p38.anclab.dsp.HeadphoneToneBank;\nimport com.p38.anclab.dsp.StationaryNoiseProfiler;')
rep(p,'private HeadphoneFeedforwardFxNlms headphoneFx;\n    private FeedbackFxNlms vehicleFx;',
      'private HeadphoneFeedforwardFxNlms headphoneFx;\n    private HeadphoneToneBank headphoneTones;\n    private final StationaryNoiseProfiler stationaryNoiseProfiler=new StationaryNoiseProfiler();\n    private long lastHeadphoneToneRevision=-1L;\n    private FeedbackFxNlms vehicleFx;')
rep(p,'HeadphoneFeedforwardFxNlms h=headphoneFx;if(h!=null)h.setUserOutputScale(scale);',
      'HeadphoneFeedforwardFxNlms h=headphoneFx;if(h!=null)h.setUserOutputScale(scale);HeadphoneToneBank ht=headphoneTones;if(ht!=null)ht.setUserOutputScale(scale);')
rep(p,'headphoneFx=new HeadphoneFeedforwardFxNlms(calibration.secondaryPath,calibration.delaySamples,calibration.safeOutputCeiling);headphoneFx.setAdaptationRate(calibration.delaySamples>2400?0.012f:0.035f);headphoneFx.setUserOutputScale(antiNoisePercent/100f);',
      'headphoneFx=new HeadphoneFeedforwardFxNlms(calibration.secondaryPath,calibration.delaySamples,calibration.safeOutputCeiling);headphoneFx.setAdaptationRate(calibration.delaySamples>2400?0.012f:0.035f);headphoneFx.setUserOutputScale(antiNoisePercent/100f);headphoneTones=new HeadphoneToneBank(calibration.safeOutputCeiling,antiNoisePercent/100f);stationaryNoiseProfiler.reset();lastHeadphoneToneRevision=-1L;')
rep(p,'inputEnergy+=in[i]*in[i];\n                if(mode==Mode.HEADPHONES&&headphoneFx!=null){\n                    y=headphoneFx.process(in[i]);ref=headphoneFx.diagnosticReference();cancel=headphoneFx.diagnosticPredictedCancellation();residual=headphoneFx.diagnosticPredictedResidual();\n                }else if(mode==Mode.VEHICLE){',
      'inputEnergy+=in[i]*in[i];stationaryNoiseProfiler.observe(in[i]);\n                if(mode==Mode.HEADPHONES&&headphoneFx!=null){\n                    float toneModel=headphoneTones==null?0f:headphoneTones.process(in[i]);\n                    if(headphoneTones!=null&&headphoneTones.frequencyRevision()!=lastHeadphoneToneRevision){lastHeadphoneToneRevision=headphoneTones.frequencyRevision();headphoneFx.setExcludedFrequencies(headphoneTones.frequenciesHz());}\n                    headphoneFx.process(in[i]);float broadModel=headphoneFx.diagnosticModelDrive();\n                    float totalModelCeiling=calibration.safeOutputCeiling*(antiNoisePercent/100f);float combinedModel=clamp(toneModel+broadModel,-totalModelCeiling,totalModelCeiling);\n                    y=clamp(combinedModel*routeGainCompensation,-0.5f,0.5f);ref=headphoneFx.diagnosticReference();cancel=headphoneFx.diagnosticPredictedCancellation()+toneModel;residual=headphoneFx.diagnosticPredictedResidual();\n                }else if(mode==Mode.VEHICLE){')
rep(p,'if(mode==Mode.HEADPHONES&&headphoneFx!=null){inputRms=headphoneFx.inputRms();outputRms=headphoneFx.outputRms();checkSafetyTrips(headphoneFx.safetyTrips(),headphoneFx.safetyStatus());}',
      'if(mode==Mode.HEADPHONES&&headphoneFx!=null){inputRms=headphoneFx.inputRms();outputRms=(float)Math.sqrt(outputEnergy/Math.max(1,n));checkSafetyTrips(headphoneFx.safetyTrips(),headphoneFx.safetyStatus());if((safetyStatus==null||safetyStatus.isEmpty())&&headphoneTones!=null)safetyStatus=headphoneTones.status();}')
rep(p,'recorder.onAudio(in,out,n);',
      'StationaryNoiseProfiler.Snapshot noise=stationaryNoiseProfiler.snapshot();String toneSummary=headphoneTones==null?"":headphoneTones.status();double confidence=headphoneFx==null?Double.NaN:headphoneFx.predictorConfidence();recorder.onAudio(in,out,n,new SessionRecorder.Diagnostics(noise.ancBandRmsDbFs(),confidence,toneSummary,noise.status(),safetyStatus));')
rep(p,'String algorithm=mode==Mode.HEADPHONES?"HEADPHONE_PREDICTIVE_FXNLMS_15_600":',
      'String algorithm=mode==Mode.HEADPHONES?"HEADPHONE_TONES_PLUS_PREDICTIVE_FXNLMS_15_600":')
rep(p,'record=null;track=null;headphoneFx=null;vehicleFx=null;vehicleNarrowband=null;',
      'record=null;track=null;headphoneFx=null;headphoneTones=null;lastHeadphoneToneRevision=-1L;vehicleFx=null;vehicleNarrowband=null;')

# Version the integrated build.
p='anclab/build.gradle.kts'
rep(p,'versionCode = 17','versionCode = 18')
rep(p,'versionName = "0.6.0-multisensor-dev"','versionName = "0.6.1-adaptive-discovery-dev"')
