# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
