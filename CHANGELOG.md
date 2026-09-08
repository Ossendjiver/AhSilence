# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.5.9.1-rebuild] — 2026-09-08

### Fixed
- Keep 8–20 Hz microphone discoveries monitor-only without allowing them to consume the bounded fallback cancellation-controller bank.
- Capacity and replacement decisions now count only cancellable discovered lanes; monitoring, logging, broadband exclusion visibility and existing output ceilings remain unchanged.

### Validation basis
- On the real P38 20–200 second WAV section with the same synthetic unity-gain 50 ms secondary path, true 33–36 Hz RUNNING time increased from 1.2 s to 17.5 s cold and from 0.0 s to 50.1 s warm in the isolated A/B.
- Total true RUNNING lane-time increased from 351.9 s to 397.7 s cold and from 433.2 s to 495.5 s warm. These are controller-orchestration replay figures, not measured in-cabin attenuation.

## [0.5.9-rebuild] — 2026-09-08

### Changed
- Reconsider persistent fallback candidates on later discovery scans instead of permanently losing a strong tone when all controller slots were occupied at first detection.
- Allow up to 10 microphone-discovered narrowband fallback lanes when speculative broadband ANC is off; broadband mode retains the conservative six-lane reserve.
- Protect active and calibrating lanes from replacement. A full bank may replace only a genuinely idle/rejected lane after its retry delay, and only for a candidate at least 6 dB stronger.
- Require at least -62 dBFS for a fallback tone to consume a cancellation controller while preserving full 8–200 Hz monitoring and logging.
- Keep the accepted pre-continuity tracking behaviour. The later single-scan continuity experiment is deliberately not included because the targeted replay lost most of the useful prop-shaft coverage.
- Model vehicle broadband loudspeaker return through the same 15–600 Hz observation path as the microphone, reconstruct the disturbance first, and only then notch frequencies owned by narrowband controllers.
- Apply the corresponding observation-path correction to predictive headphone secondary-path and filtered-X modelling.

### Validation basis
- The 20–200 second P38 targeted replay identified the dominant ~34.4 Hz prop-shaft region as a missed high-value lane under the old six-slot fallback policy.
- The selected admission-floor candidate increased warm productive cancellation from 342.3 to 354.5 lane-seconds (+3.6%) and recovered 43.8 seconds from the previously absent prop-shaft band in the synthetic-path replay.
- These WAV replays use a synthetic unity-gain 50 ms speaker path and validate controller behaviour only; real cabin attenuation still requires closed-loop in-car testing.

## [0.5.8.1-rebuild] — 2026-09-07

### Fixed
- Load P38/E46 cancellation recipes when vehicle ANC starts and append newly verified observations to the profile recipe file.
- Store the measured speaker-to-microphone complex secondary path rather than a run-specific anti-noise gain/phase command.
- On a warm run, measure the current disturbance phase, derive a fresh command from the saved path, and verify it at half strength before full output.
- Key recipes by route-calibration revision, physical model, source type and quantised speed/RPM/load/frequency bin; repeated runs are combined rather than overwritten.
- Flush learned observations when ANC stops so a short successful run is not lost between periodic writes.
- Keep frequencies below the conservatively supported vehicle output band monitor-only while retaining 8–200 Hz analysis and logging.
- Coalesce telemetry lanes as well as discovered lanes when microphone refinement makes them converge on one physical tone.
- Retry a still-strong lane after an eight-second muted pause if its first conservative path probe was rejected, rather than leaving it permanently idle while it occupies a lane slot.
- When fine adaptation repeatedly rejects a changing disturbance, retain the verified speaker path and half-strength-check a freshly inferred command instead of restarting baseline/path calibration.
- Hold an in-flight bounded lane calibration through up to 0.55 Hz of tracker motion, avoiding repeated baseline restarts while the controller is still inside its admitted physical neighbourhood.

### Tests
- Added warm-path phase, additive recipe aggregation, route isolation, monitor-only band and collision-policy regressions.
- The complete JVM-testable ANC Lab suite passes 23 tests.

## [0.5.8-rebuild] — 2026-09-07

### Changed
- Hold a narrowband controller's acoustic centre through small tracker motion while its baseline/path probe is in flight, instead of repeatedly restarting calibration.
- Follow small frequency motion during established cancellation without forcing a fresh settle/verification cycle for every update.
- Anchor microphone-discovered vehicle lanes to a bounded neighbourhood around their admission frequency.
- Coalesce independently discovered lanes if they later converge onto the same physical spectral line.
- Keep exact residual analysis and broadband exclusion centred on the controller-owned frequency while calibration hysteresis is active.

### Tests
- Added regression coverage for bounded frequency tracking and narrowband calibration retune hysteresis.
- Changes are driven by the 7 September 2026 P38 v0.5.7 full-WAV dry run; see `docs/p38-dry-run-2026-09-07.md` for evidence and limitations.

## [1.0.0] — 2025-07-04

### Added
- Real-time ambient noise analysis via custom Cooley-Tukey Radix-2 FFT engine
- Phase-inverted anti-noise emission with GC-free hot loop
- 360° phase calibration dial (CalibratorRing)
- Amplitude gain slider (GainSlider)
- Pro/Simple mode toggle for technical vs friendly UI
- Android Foreground Service (OS Shield) for background operation
- OLED-black / Neon Amber studio aesthetic
- Clean Architecture (MVVM) with strict domain isolation