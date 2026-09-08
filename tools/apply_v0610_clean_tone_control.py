from pathlib import Path


def replace_once(text, old, new, label):
    count=text.count(old)
    if count!=1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old,new,1)

root=Path(__file__).resolve().parents[1]

# ---------------------------------------------------------------------------
# SpectrumAnalyzer: controller decisions must use only samples produced under
# the CURRENT command.  A 750 ms phasor window was longer than the 420 ms Room
# settle time, contaminating every +/-, half/full path measurement with the
# previous command.  300 ms still gives 12 cycles at 40 Hz (6 at 20 Hz) while
# fitting comfortably inside the settled portion of each stage.
# ---------------------------------------------------------------------------
p=root/'anclab/src/main/java/com/p38/anclab/dsp/SpectrumAnalyzer.java'
s=p.read_text()
s=replace_once(s,
'''        int targetLength=Math.min(samples.length,Math.max(64,(int)Math.round(sampleRateHz*0.75)));\n''',
'''        // Control phasor uses a short trailing window so stage-to-stage command changes never\n        // bleed into the next acoustic-path measurement.  Discovery/peak finding still uses the\n        // full ring above; only the complex residual used by AutoController is shortened.\n        int targetLength=Math.min(samples.length,Math.max(64,(int)Math.round(sampleRateHz*0.30)));\n''','short clean control phasor window')
p.write_text(s)

# ---------------------------------------------------------------------------
# AutoController: route calibration is a gate/rough scale, not trustworthy
# enough at one low-frequency line to justify a large first cancellation trial.
# Measure a tiny symmetric LOCAL complex response first, then escalate only if
# a bounded quarter-strength trial actually reduces the tone.
# ---------------------------------------------------------------------------
p=root/'anclab/src/main/java/com/p38/anclab/dsp/AutoController.java'
s=p.read_text()
s=replace_once(s,
'''    private static final double CALIBRATED_REFINE_MAX_PATH_RATIO = 32.0;\n''',
'''    private static final double CALIBRATED_REFINE_MAX_PATH_RATIO = 32.0;\n    // Room oscillator coefficients are smoothed with a 180 ms time constant.  Wait long enough\n    // that the entire 300 ms control phasor is effectively under the current command.\n    private static final long DIRECT_ERROR_SETTLE_MS = 850L;\n    private static final double INITIAL_VERIFICATION_SCALE = 0.25;\n    private static final double INITIAL_VERIFICATION_MAX_GAIN = 0.0040;\n    private static final double INITIAL_VERIFICATION_MIN_REDUCTION_DB = 0.30;\n''','clean verification constants')
s=replace_once(s,
'''    private long settleMs() { return directErrorLearning ? 420L : SETTLE_MS; }\n    private long adaptIntervalMs() { return directErrorLearning ? 220L : ADAPT_INTERVAL_MS; }\n''',
'''    private long settleMs() { return directErrorLearning ? DIRECT_ERROR_SETTLE_MS : SETTLE_MS; }\n    private long adaptIntervalMs() { return directErrorLearning ? 220L : ADAPT_INTERVAL_MS; }\n\n    private Complex initialVerificationCommand() {\n        if (secondaryPath.magnitude() < 1.0e-9) return Complex.ZERO;\n        Complex optimum = baseline.negate().divide(secondaryPath).clampMagnitude(maximumGain);\n        double cap = Math.min(maximumGain, INITIAL_VERIFICATION_MAX_GAIN);\n        return optimum.multiply(INITIAL_VERIFICATION_SCALE).clampMagnitude(cap);\n    }\n''','settle and bounded initial verification helper')
s=replace_once(s,
'''            usingLearnedSecondaryPath = true;\n            previousCommand = Complex.ZERO;\n            previousResidual = baselineResidual;\n            command = baseline.negate().divide(secondaryPath)\n                    .clampMagnitude(maximumGain).multiply(0.5);\n            stage = Stage.VERIFY_HALF;\n            stageStartedMs = nowMs;\n            status = label + ": validating learned path at half strength…";\n            return;\n''',
'''            usingLearnedSecondaryPath = true;\n            previousCommand = Complex.ZERO;\n            previousResidual = baselineResidual;\n            // A short route-calibration FIR can have the wrong low-frequency phase.  For any\n            // calibrated/no-blind-probe lane, never use that seed for a large first command.\n            // Instead identify the local complex path with the bounded symmetric micro-pulse first.\n            if (mustRejectInsteadOfBlindProbe() && calibrationPath.magnitude() >= 1.0e-5) {\n                command = Complex.ZERO;\n                if (beginCalibratedRefinement(nowMs, "measuring local narrowband path before cancellation")) return;\n                rejectActiveVerification("could not start local calibrated-path measurement");\n                return;\n            }\n            command = initialVerificationCommand();\n            stage = Stage.VERIFY_HALF;\n            stageStartedMs = nowMs;\n            status = label + ": validating learned path at bounded initial strength…";\n            return;\n''','mandatory local path measurement before calibrated drive')
s=replace_once(s,
'''        previousCommand = command;\n        previousResidual = residual;\n        command = baseline.negate().divide(secondaryPath).clampMagnitude(maximumGain);\n        stage = Stage.VERIFY_FULL;\n        stageStartedMs = nowMs;\n        status = label + ": testing calculated level…";\n''',
'''        if (mustRejectInsteadOfBlindProbe()) {\n            double requiredInitialRatio = Math.pow(10.0, -INITIAL_VERIFICATION_MIN_REDUCTION_DB / 20.0);\n            if (residual >= baselineResidual * requiredInitialRatio) {\n                if (usingLearnedSecondaryPath) {\n                    usingLearnedSecondaryPath = false;\n                    if (beginCalibratedRefinement(nowMs, "bounded initial trial lacked measurable benefit")) return;\n                    rejectActiveVerification("locally measured path did not reduce the tone at bounded initial strength");\n                    return;\n                }\n                reject("bounded initial trial did not reduce the tone");\n                return;\n            }\n        }\n        previousCommand = command;\n        previousResidual = residual;\n        command = baseline.negate().divide(secondaryPath).clampMagnitude(maximumGain);\n        stage = Stage.VERIFY_FULL;\n        stageStartedMs = nowMs;\n        status = label + ": bounded trial reduced the tone; testing calculated level…";\n''','require benefit before full escalation')
s=replace_once(s,
'''        command = baseline.negate().divide(secondaryPath).clampMagnitude(maximumGain).multiply(0.5);\n        usingLearnedSecondaryPath = true;\n        stage = Stage.VERIFY_HALF;\n        stageStartedMs = nowMs;\n        status = String.format(Locale.US, "%s: local path refined (%.1f× calibration); verifying half strength…",\n                label, pathRatio);\n''',
'''        command = initialVerificationCommand();\n        usingLearnedSecondaryPath = true;\n        stage = Stage.VERIFY_HALF;\n        stageStartedMs = nowMs;\n        status = String.format(Locale.US, "%s: local path refined (%.1f× calibration); verifying bounded initial strength…",\n                label, pathRatio);\n''','bounded command after local refinement')
p.write_text(s)

# ---------------------------------------------------------------------------
# Tests: calibrated paths now refine BEFORE any large command.  Keep the safety
# tests explicit about the <=0.0015 identification pulse and <=0.004 first trial.
# ---------------------------------------------------------------------------
p=root/'anclab/src/test/java/com/p38/anclab/dsp/CalibratedMicroRefinementTest.java'
p.write_text('''package com.p38.anclab.dsp;\n\nimport org.junit.Test;\nimport static org.junit.Assert.assertEquals;\nimport static org.junit.Assert.assertTrue;\n\npublic class CalibratedMicroRefinementTest {\n    private static SpectrumSnapshot snapshot(AutoController c, Complex disturbance, Complex truePath) {\n        Complex residual=disturbance.add(truePath.multiply(c.output().coefficient()));\n        return new SpectrumSnapshot(68.0,residual.magnitude(),-35,12,68.0,residual,-35,-30,2.0);\n    }\n\n    @Test public void wrongCalibratedPhaseIsMeasuredLocallyBeforeAnyLargeTrial() {\n        AutoController c=new AutoController();\n        c.setDirectErrorLearning(true);\n        c.setBlindProbesAllowed(false);\n        Complex disturbance=new Complex(0.10,0.0);\n        Complex truePath=new Complex(1.0,0.0);\n        // Deliberately 90 degrees wrong.  The stored path may choose the tiny dither phase/scale,\n        // but must never be trusted for a large first cancellation command.\n        c.startTrackingWithSecondaryPath(0,0.02,68.0,"Room",false,new Complex(0.0,1.0));\n        c.update(snapshot(c,disturbance,truePath),900);\n        assertEquals("REFINE_POSITIVE",c.stageName());\n        assertTrue(c.output().gain()<=0.0015+1e-12);\n        c.update(snapshot(c,disturbance,truePath),1800);\n        assertEquals("REFINE_NEGATIVE",c.stageName());\n        assertTrue(c.output().gain()<=0.0015+1e-12);\n        c.update(snapshot(c,disturbance,truePath),2700);\n        assertEquals("VERIFY_HALF",c.stageName());\n        assertTrue(c.output().gain()<=0.0040+1e-12);\n        c.update(snapshot(c,disturbance,truePath),3600);\n        assertEquals("VERIFY_FULL",c.stageName());\n        c.update(snapshot(c,disturbance,truePath),4500);\n        assertEquals("AUDIT_OFF",c.stageName());\n        c.update(snapshot(c,disturbance,truePath),5400);\n        c.update(snapshot(c,disturbance,truePath),6300);\n        assertEquals("RUNNING",c.stageName());\n        assertTrue(c.hasPassedActiveVerification());\n        assertTrue(c.currentImprovementDb()>1.0);\n    }\n\n    @Test public void unmeasurableLocalPathStillQuarantinesWithoutEscalating() {\n        AutoController c=new AutoController();\n        c.setDirectErrorLearning(true);\n        c.setBlindProbesAllowed(false);\n        Complex disturbance=new Complex(0.10,0.0);\n        Complex noAcousticResponse=Complex.ZERO;\n        c.startTrackingWithSecondaryPath(0,0.02,68.0,"Room",false,new Complex(0.0,1.0));\n        c.update(snapshot(c,disturbance,noAcousticResponse),900);\n        assertEquals("REFINE_POSITIVE",c.stageName());\n        assertTrue(c.output().gain()<=0.0015+1e-12);\n        c.update(snapshot(c,disturbance,noAcousticResponse),1800);\n        assertEquals("REFINE_NEGATIVE",c.stageName());\n        c.update(snapshot(c,disturbance,noAcousticResponse),2700);\n        assertEquals("IDLE",c.stageName());\n        assertTrue(c.activeVerificationFailed());\n        assertEquals(0.0,c.output().gain(),1e-12);\n    }\n}\n''')

p=root/'anclab/src/test/java/com/p38/anclab/dsp/RoomNoBlindProbeTest.java'
s=p.read_text()
s=replace_once(s,
'''        c.update(snapshot(1.0),1500);\n        c.update(snapshot(1.20),2000);\n        assertEquals("REFINE_POSITIVE",c.stageName());\n        assertTrue(c.output().gain()<=0.0015+1e-12);\n        // No measurable +/- acoustic response: refinement must stop rather than escalating to\n        // the old blind secondary-path probe.\n        c.update(snapshot(1.0),2500);\n        assertEquals("REFINE_NEGATIVE",c.stageName());\n        assertTrue(c.output().gain()<=0.0015+1e-12);\n        c.update(snapshot(1.0),3000);\n''',
'''        c.update(snapshot(1.0),1900);\n        assertEquals("REFINE_POSITIVE",c.stageName());\n        assertTrue(c.output().gain()<=0.0015+1e-12);\n        // No measurable +/- acoustic response: refinement must stop rather than escalating to\n        // a calculated cancellation command or the old blind secondary-path probe.\n        c.update(snapshot(1.0),2800);\n        assertEquals("REFINE_NEGATIVE",c.stageName());\n        assertTrue(c.output().gain()<=0.0015+1e-12);\n        c.update(snapshot(1.0),3700);\n''','Room safety expected mandatory refinement')
p.write_text(s)

p=root/'anclab/src/test/java/com/p38/anclab/dsp/SharedCalibratedSafetyTest.java'
s=p.read_text()
s=replace_once(s,
'''        c.update(snapshot(1.0),1800);\n        c.update(snapshot(1.20),2600);\n        assertEquals("REFINE_POSITIVE",c.stageName());\n        assertTrue(c.output().gain()<=0.0015+1e-12);\n        // If the tiny symmetric measurement cannot resolve a local transfer, fail closed.  This\n        // verifies that vehicle mode does not fall back to the former large blind reprobe.\n        c.update(snapshot(1.0),3400);\n        assertEquals("REFINE_NEGATIVE",c.stageName());\n        assertTrue(c.output().gain()<=0.0015+1e-12);\n        c.update(snapshot(1.0),4200);\n''',
'''        c.update(snapshot(1.0),1800);\n        assertEquals("REFINE_POSITIVE",c.stageName());\n        assertTrue(c.output().gain()<=0.0015+1e-12);\n        // If the tiny symmetric measurement cannot resolve a local transfer, fail closed.  This\n        // verifies that vehicle mode does not fall back to the former large blind reprobe.\n        c.update(snapshot(1.0),2600);\n        assertEquals("REFINE_NEGATIVE",c.stageName());\n        assertTrue(c.output().gain()<=0.0015+1e-12);\n        c.update(snapshot(1.0),3400);\n''','vehicle safety expected mandatory refinement')
p.write_text(s)

# Regression for the exact field failure: the controller phasor must represent
# the newest command interval rather than averaging it with the previous state.
p=root/'anclab/src/test/java/com/p38/anclab/dsp/SpectrumAnalyzerControlWindowTest.java'
p.write_text('''package com.p38.anclab.dsp;\n\nimport org.junit.Test;\nimport static org.junit.Assert.assertTrue;\n\npublic class SpectrumAnalyzerControlWindowTest {\n    @Test public void controlPhasorUsesOnlyRecentSettledCommand() {\n        double sr=500.0,f=120.0;\n        int n=1100;float[] x=new float[n];\n        // Old controller state for most of the analysis ring.\n        for(int i=0;i<n-150;i++)x[i]=(float)Math.cos(2.0*Math.PI*f*i/sr);\n        // Latest 300 ms is a 90-degree-shifted state.  A 750 ms control window would mix both\n        // states; the clean trailing 300 ms window should report approximately +90 degrees.\n        for(int i=n-150;i<n;i++)x[i]=(float)Math.cos(2.0*Math.PI*f*i/sr+Math.PI/2.0);\n        SpectrumSnapshot s=SpectrumAnalyzer.analyze(x,0,sr,118.0,122.0,f,0,0.0);\n        double phase=s.targetComplex().phaseRadians();\n        assertTrue("latest command phase should dominate, got "+phase,Math.abs(phase-Math.PI/2.0)<0.12);\n        assertTrue(s.targetComplex().magnitude()>0.90);\n    }\n}\n''')

# Version bump.
p=root/'anclab/build.gradle.kts'
s=p.read_text()
s=replace_once(s,
'''        versionCode = 27\n        versionName = "0.6.9-visible-coexistent-discovery-dev"\n''',
'''        versionCode = 28\n        versionName = "0.6.10-clean-tone-control-dev"\n''','version bump')
s=s.replace('// ANC v0.6.9 separates persistent-frequency observation from controller admission and allows safe discovery to coexist with telemetry.',
            '// ANC v0.6.10 uses clean non-overlapping complex measurements and mandatory tiny local path identification before any calibrated narrowband drive.')
p.write_text(s)
