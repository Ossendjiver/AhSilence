# Supplied v0.5 source inventory

This inventory records the disposition of every major component in the user-supplied
`ANC-Lab-v0.5.0-source-and-documentation.zip`. See `BACKGROUND_MERGE.md` for conflict rationale.

## Integrated into the active module

- `docs/ALGORITHM.md` → reconciled into `anclab/docs/ALGORITHM.md`.
- `THIRD_PARTY_NOTICES.md` → restored as `anclab/THIRD_PARTY_NOTICES.md`.
- `dsp/AdaptiveFrequencyTracker.java` → restored.
- `dsp/AutoController.java` → restored for vehicle-side narrowband control.
- `dsp/BroadbandDetector.java` → restored for coherent vehicle spectral-line discovery.
- `dsp/BroadbandSafetyGuard.java` → restored.
- `dsp/ButterworthLowPass.java` → restored.
- `dsp/Complex.java` → restored.
- `dsp/DcBlocker.java` → restored.
- `dsp/LatencyEstimator.java` → restored.
- `dsp/Radix2Spectrum.java` → restored.
- `dsp/SpectrumAnalyzer.java` → restored.
- `dsp/SpectrumSnapshot.java` → restored.
- `profile/MechanicalFrequency.java` → restored.
- `profile/ProfileStore.java` → its non-conflicting P38/E46/profile defaults and separation are
  integrated into the current SAF-backed `ProfileStore`; the old app-scoped filesystem is not.
- `spl/MicCalibration.java` → restored.
- `spl/SplMeter.java` → restored.
- `telemetry/GpsTelemetry.java` → restored.
- `telemetry/ObdClient.java` → restored.
- `telemetry/TelemetryState.java` → restored.
- relevant manifest location/Bluetooth permissions → restored without activating media-browser or
  connection-autostart behaviour.
- representative unit tests for latency, DC blocking, mechanical scaling and microphone calibration
  → restored, and CI now runs unit tests before building the APK.

## Functionality already represented by newer active code

- `recording/MonitoringLog.java` → newer SAF-backed monitoring log retained.
- `recording/SessionRecorder.java` → newer Headphones stereo reference + cancellation-drive recorder
  retained; the archived mono recorder is superseded for Headphones.
- background wake lock / foreground execution from `AncMediaService.java` → represented by the newer
  plain foreground service, hardened for microphone FGS and task dismissal.
- input/output routing and `UNPROCESSED` microphone selection → represented by the current
  `audio/AudioEngine.java`.
- per-profile storage → represented by the current SAF-backed `AncStorage` + reconciled `ProfileStore`.

## Preserved as design, not copied wholesale because it conflicts

- archived `audio/AudioEngine.java`: vehicle engine logic remains a porting reference, but replacing
  the active engine would remove predictive Headphones FxNLMS, the live graph and current recorder.
- archived `MainActivity.java`: contains the richer vehicle/SPL/settings UI; it is not copied over the
  active Headphones UI because that would regress current controls and graph semantics.
- archived `AncRuntime.java`: tightly coupled to the archived engine/filesystem; its telemetry,
  profile and recipe concepts are being ported independently.
- archived `AncMediaService.java`: Android Auto `MediaBrowserService`/`MediaSession` is intentionally
  not activated because presenting ANC Lab as a media source can cause head-unit source switching.
- archived `ConnectionReceiver.java`: connection-trigger/autostart is not activated until it can be
  wired as notification-only or explicit opt-in without unexpectedly starting or foregrounding ANC.
- archived `LatencyProfileStore.java` and `RecipeStore.java`: their append-only per-profile semantics
  are retained in the storage/profile design; their exact classes depend on archived `AudioEngine`
  record types and will be ported when the current vehicle runtime exposes equivalent state.
- archived drawable/Android Auto XML resources: not activated while the media-browser surface is
  intentionally disabled.

## Explicitly superseded by the reconstruction chat

- archived Headphones three-minute narrowband recipe controller;
- archived assumption that Headphones uses the same narrowband/coherent controller as vehicles;
- archived top-level/app-scoped `ANC/P38`, `ANC/E46`, `ANC/Headphones` filesystem;
- archived mono-only session recording for Headphones;
- any interpretation of the phone microphone as a live in-ear error microphone during normal IEM
  use.

The archive itself remains the provenance source for the components above; its SHA-256 is recorded in
`BACKGROUND_MERGE.md`.
