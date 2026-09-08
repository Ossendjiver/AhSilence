from pathlib import Path


def replace_once(text, old, new, label):
    count=text.count(old)
    if count!=1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old,new,1)

root=Path(__file__).resolve().parents[1]

# Room persistence may legitimately wander by a few Hz. Preserve the strict default for vehicles.
p=root/'anclab/src/main/java/com/p38/anclab/dsp/BroadbandDetector.java'
s=p.read_text()
s=replace_once(s,
'''    public synchronized List<Candidate> update(List<SpectrumAnalyzer.DetectedTone> detections, long nowMs) {
        for (Track track : tracks) track.seenThisScan = false;
''',
'''    public synchronized List<Candidate> update(List<SpectrumAnalyzer.DetectedTone> detections, long nowMs) {
        return update(detections,nowMs,MATCH_RADIUS_HZ);
    }

    public synchronized List<Candidate> update(List<SpectrumAnalyzer.DetectedTone> detections, long nowMs,
                                                double matchRadiusHz) {
        matchRadiusHz=Math.max(MATCH_RADIUS_HZ,Math.min(6.0,matchRadiusHz));
        for (Track track : tracks) track.seenThisScan = false;
''','BroadbandDetector overload')
s=replace_once(s,
'''                if (!track.seenThisScan && distance <= MATCH_RADIUS_HZ && distance < bestDistance) {
''',
'''                if (!track.seenThisScan && distance <= matchRadiusHz && distance < bestDistance) {
''','BroadbandDetector radius')
p.write_text(s)

# Allow the direct Room controller to refresh its calibrated complex path as a tracked tone moves.
p=root/'anclab/src/main/java/com/p38/anclab/dsp/AutoController.java'
s=p.read_text()
anchor='''    public synchronized void setMaximumGain(double maximumGain) {
        this.maximumGain = clamp(maximumGain, 0.0001, 0.15);
        command = command.clampMagnitude(this.maximumGain);
    }
'''
insert=anchor+'''
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
'''
s=replace_once(s,anchor,insert,'AutoController path refresh')
p.write_text(s)

p=root/'anclab/src/main/java/com/p38/anclab/dsp/VehicleNarrowbandBank.java'
s=p.read_text()
s=replace_once(s,
'''    private static final double ROOM_MIN_CALIBRATED_PATH_MAGNITUDE=1.0e-5;
''',
'''    private static final double ROOM_MIN_CALIBRATED_PATH_MAGNITUDE=1.0e-5;
    private static final double ROOM_DISCOVERY_MATCH_RADIUS_HZ=2.50;
    private static final double ROOM_DISCOVERY_SEARCH_HALF_WIDTH_HZ=2.40;
    private static final double ROOM_DISCOVERY_TRACK_HALF_WIDTH_HZ=3.50;
''','Room discovery constants')

s=replace_once(s,
'''            List<BroadbandDetector.Candidate> ready=new ArrayList<>(discoveryDetector.update(peaks,now));
            ready.sort(Comparator.comparingDouble(BroadbandDetector.Candidate::score).reversed());
''',
'''            List<BroadbandDetector.Candidate> ready=new ArrayList<>(directFeedbackLearning
                    ?discoveryDetector.update(peaks,now,ROOM_DISCOVERY_MATCH_RADIUS_HZ)
                    :discoveryDetector.update(peaks,now));
            ready.sort((a,b)->{
                if(directFeedbackLearning){
                    int level=Double.compare(b.dbFs(),a.dbFs());
                    if(level!=0)return level;
                }
                return Double.compare(b.score(),a.score());
            });
''','Room dominant candidate ranking')

s=replace_once(s,
'''                DiscoveredLane lane=new DiscoveredLane(candidate.id(),candidate.frequencyHz(),now);
                lane.lastPeakDbFs=candidate.dbFs();
''',
'''                DiscoveredLane lane=new DiscoveredLane(candidate.id(),candidate.frequencyHz(),now);
                if(directFeedbackLearning)lane.tracker.setBounds(
                        Math.max(8.0,candidate.frequencyHz()-ROOM_DISCOVERY_TRACK_HALF_WIDTH_HZ),
                        Math.min(200.0,candidate.frequencyHz()+ROOM_DISCOVERY_TRACK_HALF_WIDTH_HZ));
                lane.lastPeakDbFs=candidate.dbFs();
''','Room tracker bounds')

s=replace_once(s,
'''            SpectrumSnapshot search=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,
                    Math.max(8.0,centre-DISCOVERY_SEARCH_HALF_WIDTH_HZ),
                    Math.min(200.0,centre+DISCOVERY_SEARCH_HALF_WIDTH_HZ),centre,l.referenceEpoch,l.referencePhase);
            boolean lock=search.peakDbFs()>-112.0&&search.contrastDb()>1.5&&Math.abs(search.peakFrequencyHz()-centre)<=DISCOVERY_SEARCH_HALF_WIDTH_HZ;
''',
'''            double discoverySearchHalfWidth=directFeedbackLearning
                    ?ROOM_DISCOVERY_SEARCH_HALF_WIDTH_HZ:DISCOVERY_SEARCH_HALF_WIDTH_HZ;
            SpectrumSnapshot search=SpectrumAnalyzer.analyze(window,first,ANALYSIS_RATE,
                    Math.max(8.0,centre-discoverySearchHalfWidth),
                    Math.min(200.0,centre+discoverySearchHalfWidth),centre,l.referenceEpoch,l.referencePhase);
            boolean lock=search.peakDbFs()>-112.0&&search.contrastDb()>1.5
                    &&Math.abs(search.peakFrequencyHz()-centre)<=discoverySearchHalfWidth;
''','Room search width')

s=replace_once(s,
'''                if(Math.abs(refined-l.currentFrequencyHz)>0.025){l.currentFrequencyHz=refined;moved=true;}
                if(l.cancellable)l.controller.followFrequency(refined,now);
''',
'''                if(Math.abs(refined-l.currentFrequencyHz)>0.025){l.currentFrequencyHz=refined;moved=true;}
                if(l.cancellable){
                    l.controller.followFrequency(refined,now);
                    if(directFeedbackLearning){
                        double controlled=l.controller.output().frequencyHz();
                        l.controller.refreshSecondaryPath(SecondaryPathFrequencyResponse.at(
                                calibratedSecondaryPathFir,calibratedDelaySamples,calibratedSampleRateHz,controlled));
                    }
                }
''','Room live path refresh')

s=s.replace('Room calibrated-path feedback · quiet-discovering stable 8–200 Hz lines',
            'Room calibrated-path feedback · dominant-mode tracking 40–200 Hz')
p.write_text(s)

# Version.
p=root/'anclab/build.gradle.kts'
s=p.read_text()
s=replace_once(s,
'''        versionCode = 23
        versionName = "0.6.5-room-calibrated-path-dev"
''',
'''        versionCode = 24
        versionName = "0.6.6-room-dominant-tracking-dev"
''','version bump')
s=s.replace('Room ANC v0.6.5 seeds narrowband control from measured route calibration and forbids blind Room probes.',
            'Room ANC v0.6.6 prioritises dominant modes, tolerates Room-frequency wander, and refreshes the calibrated path while tracking.')
p.write_text(s)

# Regression test for Room persistence radius.
p=root/'anclab/src/test/java/com/p38/anclab/dsp/RoomDiscoveryTrackingTest.java'
p.write_text('''package com.p38.anclab.dsp;\n\nimport org.junit.Test;\nimport java.util.List;\nimport static org.junit.Assert.assertFalse;\nimport static org.junit.Assert.assertTrue;\n\npublic class RoomDiscoveryTrackingTest {\n    private static SpectrumAnalyzer.DetectedTone tone(double hz,double db){\n        double amplitude=Math.pow(10.0,db/20.0);\n        return new SpectrumAnalyzer.DetectedTone(hz,amplitude,db,db-14.0,14.0);\n    }\n\n    @Test public void roomRadiusKeepsModeratelyWanderingDominantModePersistent(){\n        BroadbandDetector detector=new BroadbandDetector();\n        detector.update(List.of(tone(131.4,-60)),0,2.5);\n        detector.update(List.of(tone(132.7,-60)),550,2.5);\n        List<BroadbandDetector.Candidate> ready=detector.update(List.of(tone(131.9,-60)),1100,2.5);\n        assertFalse(ready.isEmpty());\n        assertTrue(ready.get(0).frequencyHz()>130.0&&ready.get(0).frequencyHz()<134.0);\n    }\n\n    @Test public void defaultRadiusStillRejectsThatJumpForVehicleFallback(){\n        BroadbandDetector detector=new BroadbandDetector();\n        detector.update(List.of(tone(131.4,-60)),0);\n        detector.update(List.of(tone(132.7,-60)),550);\n        List<BroadbandDetector.Candidate> ready=detector.update(List.of(tone(131.9,-60)),1100);\n        assertTrue(ready.isEmpty());\n    }\n}\n''')
