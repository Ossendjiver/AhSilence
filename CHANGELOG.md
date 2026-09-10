# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.5.9.7-recovery] — 2026-09-10

### Added
- Add a two-pass 20–200 Hz output-to-microphone capability sweep stored against the physical output, physical microphone and media-volume setting.
- Keep unsupported frequencies visible for monitoring while preventing narrowband emission, and block broadband mode when the measured route has inadequate coverage.

### Changed
- Make the two-step microphone benchmark survive input switching and app recreation for ten minutes, start independent SPL capture automatically, and clearly distinguish the calibrated reference from the phone microphone.
- Reject a stored output sweep when the microphone or media-volume setting differs materially from the measured route.

### Analysis
- Reviewed `session-20260910-092219.wav` and `session-20260910-092632.wav`. Both contain substantial digital anti-noise commands, but show essentially no repeatable authority at the dominant approximately 35 Hz line through the phone speaker. Portions near 120 Hz moved by several decibels, confirming that target eligibility must be based on measured output bandwidth rather than requested controller gain.

## [0.5.9.6-recovery] — 2026-09-10

### Changed
- Treat GPS/OBD mechanical predictions as search hints and require a repeatable microphone-confirmed narrowband line before emitting.
- Continuously verify each target with muted/active comparisons, require two repeatable reductions before increasing gain, and immediately mute/reacquire phase at a 1.5 dB worsening.
- Reduce the cancellation ceiling for 20–40 Hz targets while verification is active.
- Store microphone response and absolute SPL calibration separately under stable physical-device identities. UMIK sensitivity metadata is no longer converted into absolute SPL.

### Added
- Add a four-pair, 160-second ANC OFF/ON evaluation recording from the SPL page and broadcast `test cycle complete` after saving its WAV and CSV.

## [0.5.9.5-recovery] — 2026-09-09

### Changed
- Replace the vehicle controller's equal `1/N` lane split with a guarded multitone RMS budget. With ten active P38 targets at the default 50% setting, each lane can now reach about 0.0091 full-scale instead of 0.0040 (2.28× more probe and cancellation authority).
- Keep the combined generated signal inside the existing profile/user hard ceiling, so increased per-tone authority does not bypass the master anti-noise limit or the louder-than-baseline mute/reacquisition watchdog.

### Analysis
- Reviewed the 468.5-second `session-20260909-174429.wav` field recording. Its generated-output channel was present throughout, but median output RMS was only 0.00785 and the strongest emitted tone was typically about 0.005 full-scale, confirming per-lane allocation—not the combined hard limiter—was the practical restriction.

## [0.5.9.4-recovery] — 2026-09-09

### Added
- Measure route delay with two independent pseudo-random probes, persist both delay and confidence results, and show their absolute difference as a route-stability figure.
- Warn when the repeated delay measurements differ by more than 5 ms. Runtime A/B verification windows use the slower measured delay so stale anti-noise is not scored as a new command.
- Continuously report measured narrowband reduction while a cancellation lane is running.

### Safety
- If a lane remains more than 3% louder than its muted baseline for 450 ms, immediately mute it, wait for a clean acoustic window, reacquire the disturbance phase, and verify the replacement command at half strength before resuming.

## [0.5.9.3-recovery] — 2026-09-09

### Added
- Show a prominent warning whenever a Bluetooth microphone or output is selected, recommending narrowband-first operation and broadband-off until the route proves stable.
- Show the matching stored latency and correlation quality directly beside Bluetooth routing, with a soft recheck recommendation after seven days. The existing single route-calibration test remains the lightweight latency check; no continuous latency chasing was added.

## [0.5.9.2-recovery] — 2026-09-08

### Added
- Restore the dark three-page app surface with an independent SPL meter, WAV/session recording, route-preserving audio selectors and compact information dialogs instead of persistent help copy.
- Add editable P38, E46 and separate conservative headphone profiles. Vehicle profiles store expandable telemetry-linked mechanical-frequency models under `Documents/ANC/profiles/<profile>`.
- Add route-specific microphone calibration imports, a persistent default reference microphone, and a three-second reference/selected-microphone SPL benchmark workflow that does not require ANC.
- Add collapsible settings for Android Auto, screen wake, device autostart, continuous monitoring logs, selected audio routes and a paired Bluetooth OBD2 adaptor.
- Add Android Auto/media actions for Start, Auto, Record, Stop and independent SPL; Auto and Record remain available while ANC is stopped and start the prepared vehicle profile when selected.
- Add an original adaptive outline `ANC` launcher mark compatible with monochrome icon theming and the thin-outline visual language used by Lines Free.

### Fixed
- Use the stored route-calibration delay in every vehicle narrowband controller and wait for the transport delay plus a completely fresh six-cycle observation window before measuring a changed output command.
- Require a successful command to repeat against a new adjacent muted baseline before entering `RUNNING` or becoming eligible for recipe storage.
- Detect quiet but locally prominent stable lines without adopting v0.6's unsafe -110 dBFS admission floor.
- Replace v0.6's allocation-heavy 16k discovery analysis with a bounded sampled local median and 4k FFT at the 500 Hz analysis rate.
- Record diagnostic WAV files as 32-bit float so low-level anti-noise is not quantised away.
- Include per-lane frequency, gain, controller stage, measured improvement and telemetry/status text in both explicit session CSVs and the optional background monitoring log.
- Stop visibly after one second of exact digital microphone silence instead of continuing with an invalid capture stream.
- Keep ANC and standalone SPL alive under a foreground microphone/media service and partial wake lock while the screen is off; the optional screen-wake preference is independent.
- Preserve selected input/output devices across refreshes by stable route name unless the endpoint has actually disappeared.
- Make Start Log start ANC first, and make stopping ANC stop and save any active WAV/CSV session.
- Restore the saved input and its microphone calibration when standalone SPL is launched from Android Auto.

### Validation basis
- Production discovery replayed the first 12 seconds of the v0.6.12 120 Hz recording and the final reinstalled-v0.5.8 recording with 100% raw target hits, 91.3% mature-candidate coverage and first eligibility at 1.6 seconds.
- A delayed closed-loop regression uses the measured 430 ms Bluetooth route, a trailing 300 ms analyzer window and a synthetic acoustic secondary path; the recovered controller reaches confirmed `RUNNING` with more than 6 dB modelled reduction.
- The full JVM-testable ANC Lab suite passes 35 tests. See `docs/v059-recovery-audit-2026-09-08.md` for the version and recording audit, measured limitations and rejected v0.6 changes.

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
