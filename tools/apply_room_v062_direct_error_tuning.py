from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text()
    if old not in text:
        raise RuntimeError(f"pattern not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


def replace_all(path, old, new, minimum=1):
    p = ROOT / path
    text = p.read_text()
    count = text.count(old)
    if count < minimum:
        raise RuntimeError(f"expected >= {minimum} matches in {path}, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new))

# Room has a fixed error microphone that directly observes the acoustic result. Let its narrowband
# controller correct more often, while retaining the same maximum output gain and verification gates.
A = "anclab/src/main/java/com/p38/anclab/dsp/AutoController.java"
replace_once(A,
    '    private boolean fixedTarget;\n    private String label = "Dominant";\n',
    '    private boolean fixedTarget;\n    private boolean directErrorLearning;\n    private String label = "Dominant";\n')
replace_once(A,
    '    public synchronized void setMaximumGain(double maximumGain) {\n',
    '    public synchronized void setDirectErrorLearning(boolean enabled) { directErrorLearning = enabled; }\n\n'
    '    private long settleMs() { return directErrorLearning ? 420L : SETTLE_MS; }\n'
    '    private long adaptIntervalMs() { return directErrorLearning ? 220L : ADAPT_INTERVAL_MS; }\n\n'
    '    public synchronized void setMaximumGain(double maximumGain) {\n')
replace_all(A, 'elapsed(nowMs) < SETTLE_MS', 'elapsed(nowMs) < settleMs()', minimum=8)
replace_all(A, 'elapsed(nowMs) >= SETTLE_MS', 'elapsed(nowMs) >= settleMs()', minimum=1)
replace_once(A, 'elapsed(nowMs) >= ADAPT_INTERVAL_MS', 'elapsed(nowMs) >= adaptIntervalMs()')
replace_once(A,
    '            double stepSize = 0.06 * (1.0 - agileWeight) + 0.20 * agileWeight;\n'
    '            double normalization = secondaryPath.magnitudeSquared() + 1.0e-10;\n'
    '            Complex gradient = secondaryPath.conjugate().multiply(error)\n'
    '                    .multiply(-stepSize / normalization).clampMagnitude(maximumGain * 0.18);',
    '            double stepSize = directErrorLearning\n'
    '                    ? 0.10 * (1.0 - agileWeight) + 0.28 * agileWeight\n'
    '                    : 0.06 * (1.0 - agileWeight) + 0.20 * agileWeight;\n'
    '            double normalization = secondaryPath.magnitudeSquared() + 1.0e-10;\n'
    '            double correctionLimit = maximumGain * (directErrorLearning ? 0.24 : 0.18);\n'
    '            Complex gradient = secondaryPath.conjugate().multiply(error)\n'
    '                    .multiply(-stepSize / normalization).clampMagnitude(correctionLimit);')

N = "anclab/src/main/java/com/p38/anclab/dsp/VehicleNarrowbandBank.java"
replace_once(N,
    '    private void startTelemetryController(Lane lane,long now){\n        double source=telemetry==null?Double.NaN:telemetry.sourceValue(lane.model);',
    '    private void startTelemetryController(Lane lane,long now){\n'
    '        lane.controller.setDirectErrorLearning(directFeedbackLearning);\n'
    '        double source=telemetry==null?Double.NaN:telemetry.sourceValue(lane.model);')
replace_once(N,
    '    private void startDiscoveredController(DiscoveredLane lane,long now){\n        VehicleCancellationRecipe recipe=recipeBook.find(routeKey,"discovered",',
    '    private void startDiscoveredController(DiscoveredLane lane,long now){\n'
    '        lane.controller.setDirectErrorLearning(directFeedbackLearning);\n'
    '        VehicleCancellationRecipe recipe=recipeBook.find(routeKey,"discovered",')

E = "anclab/src/main/java/com/p38/anclab/audio/AudioEngine.java"
replace_once(E,
    '        if(!enabled){vehicleFx=null;safetyStatus="Speculative broadband OFF · telemetry narrowband lanes continue";}\n'
    '        else if(calibration!=null){vehicleFx=new FeedbackFxNlms(calibration.secondaryPath,calibration.delaySamples,128,calibration.safeOutputCeiling);configureVehicleFx(vehicleFx);if(bank!=null)vehicleFx.setExcludedFrequencies(bank.frequenciesHz());safetyStatus="Speculative broadband ON · live predictable lane bands excluded";}',
    '        if(!enabled){vehicleFx=null;safetyStatus=mode==Mode.ROOM?"Room broadband OFF · learned narrowband lanes continue":"Speculative broadband OFF · telemetry narrowband lanes continue";}\n'
    '        else if(calibration!=null){vehicleFx=new FeedbackFxNlms(calibration.secondaryPath,calibration.delaySamples,128,calibration.safeOutputCeiling);configureVehicleFx(vehicleFx);if(bank!=null)vehicleFx.setExcludedFrequencies(bank.frequenciesHz());safetyStatus=mode==Mode.ROOM?"Room broadband ON · direct residual adaptation active":"Speculative broadband ON · live predictable lane bands excluded";}')
replace_once(E,
    '                VehicleTelemetryRuntime telemetryForBank=null;\n                boolean room=requested==Mode.ROOM;\n',
    '                VehicleTelemetryRuntime telemetryForBank=null;\n'
    '                boolean room=requested==Mode.ROOM;\n'
    '                stationaryNoiseProfiler.reset();\n')
replace_once(E,
    'String nb=vehicleNarrowband==null?"":vehicleNarrowband.status();if((safetyStatus==null||safetyStatus.isEmpty()||safetyStatus.startsWith("Predictable narrowband"))&&!nb.isEmpty())safetyStatus=nb+(vehicleBroadbandEnabled?" · broadband ON":" · broadband OFF");',
    'String nb=vehicleNarrowband==null?"":vehicleNarrowband.status();if((mode==Mode.ROOM||safetyStatus==null||safetyStatus.isEmpty()||safetyStatus.startsWith("Predictable narrowband"))&&!nb.isEmpty())safetyStatus=nb+(vehicleBroadbandEnabled?" · broadband ON":" · broadband OFF");')

print("Room direct-error adaptation tuning applied")
