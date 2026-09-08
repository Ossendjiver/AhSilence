from pathlib import Path


def replace_once(text, old, new, label):
    n=text.count(old)
    if n!=1:
        raise RuntimeError(f"{label}: expected 1 match, found {n}")
    return text.replace(old,new,1)

root=Path(__file__).resolve().parents[1]
p=root/'anclab/src/main/java/com/p38/anclab/dsp/AutoController.java'
s=p.read_text()

# The 300 ms trailing phasor is now shorter than the existing 420 ms Room stage cadence,
# so it no longer straddles the previous controller state. Keep the established cadence to
# avoid adding unnecessary acquisition latency. The tiny +/- local refinement remains bounded.
s=replace_once(s,
'''    private static final long DIRECT_ERROR_SETTLE_MS = 850L;\n''',
'''    private static final long DIRECT_ERROR_SETTLE_MS = 420L;\n''','restore Room cadence with short clean phasor')

# Generic controllers that still permit blind path probing retain their pre-v0.6.10 half-strength
# warm start. The new bounded quarter-strength path is specifically for fail-closed calibrated
# lanes (Room and calibrated E46/P38), which call setBlindProbesAllowed(false).
s=replace_once(s,
'''            command = initialVerificationCommand();\n            stage = Stage.VERIFY_HALF;\n            stageStartedMs = nowMs;\n            status = label + ": validating learned path at bounded initial strength…";\n            return;\n''',
'''            command = baseline.negate().divide(secondaryPath)\n                    .clampMagnitude(maximumGain).multiply(0.5);\n            stage = Stage.VERIFY_HALF;\n            stageStartedMs = nowMs;\n            status = label + ": validating learned path at half strength…";\n            return;\n''','restore generic learned-path half trial')

p.write_text(s)
