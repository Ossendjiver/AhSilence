# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

### Tests
- Added warm-path phase, additive recipe aggregation, route isolation, monitor-only band and collision-policy regressions.
- The complete JVM-testable ANC Lab suite passes 22 tests.

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
