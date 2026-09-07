# ANC Lab v0.5 background-source reconciliation

Source reviewed: `ANC-Lab-v0.5.0-source-and-documentation.zip`

SHA-256 of the supplied archive:

`5035124b6cb4b88f09ba89c7431b77479053f0fa4c14075e48133d2c47eb198c`

The archive contains a substantially more complete earlier ANC Lab implementation than could be
reconstructed from the APK alone. This document records what is being restored and which portions
are intentionally not allowed to replace later decisions made during the reconstruction chat.

## Restored or retained

The following background remains compatible with the current application and is being retained in
source form or profile metadata:

- P38, E46 and Headphones as logically independent profiles.
- P38 prop-shaft and engine-order defaults; E46 starts with an empty mechanical-frequency list.
- GPS and generic Bluetooth ELM327 telemetry concepts.
- Mechanical models tied to best speed, GPS speed, OBD speed, RPM, engine load, throttle or fixed
  frequency using `f_predicted = f_ref × x_current / x_ref`.
- Quiet/agile adaptive frequency tracking.
- DC blocking, 210 Hz eighth-order Butterworth filtering, decimated analysis and FFT peak discovery.
- Narrowband phase-continuous vehicle synthesis and balanced positive/negative secondary-path
  measurement.
- Bounded complex filtered-X adaptation, worsening-step rollback and path remeasurement.
- Coherent 8–200 Hz vehicle line discovery and whole-band safety cooldown.
- REW/miniDSP microphone calibration parsing and relative/calibrated SPL statistics.
- Per-profile latency and recipe concepts.
- Background monitoring, session logging and user-accessible profile data as product requirements.
- Foreground execution with a partial CPU wake lock.
- The explicit design rule that ANC Lab does not request Android audio focus.

## Major conflicts resolved in favour of the current chat

### 1. Headphones algorithm

The archived source uses the vehicle-style narrowband/coherent controller and a conservative
three-minute Headphones bench recipe. Later requirements explicitly replace this for Headphones.
The active mode is now a generic predictive broadband 128-tap feed-forward FxNLMS system with:

- phone microphone as external reference only during normal IEM use;
- 128-tap direct-horizon predictor;
- 128-tap cancellation controller;
- stored 128-tap secondary-path FIR;
- prediction horizon based on measured calibration latency;
- no claim of a live in-ear error measurement.

The archived narrowband controller remains relevant to P38/E46, not as a replacement for active
Headphones DSP.

### 2. Storage

The archive uses separate app-scoped profile directories such as `ANC/P38`, `ANC/E46` and
`ANC/Headphones`. The current requirement is one shared, user-accessible
`Internal storage/Documents/ANC` tree selected through SAF. That layout remains authoritative.
Logical profile separation is implemented beneath `Documents/ANC/profiles` and `recipes`.

### 3. Recording

The archive records an exact mono microphone WAV plus a controller/telemetry CSV. Current Headphones
diagnostics deliberately record stereo microphone-reference + generated-cancellation-drive WAV so
controller output can be verified directly. The current Headphones recorder is retained.

### 4. Android Auto / media browser

The archive exposes ANC Lab as a `MediaBrowserService`/`MediaSession` for Android Auto. This is not
activated in the current build. Even without requesting audio focus, becoming an Android Auto media
source can cause a head unit to switch away from another media app. The current requirement is that
ANC continue in the background **without intentionally interrupting other audio apps**, so ANC Lab
uses a plain microphone/media foreground service instead.

An Android Auto control surface can be ported later as an explicit opt-in if it can be demonstrated
to coexist with the user's head unit and chosen media app.

### 5. Full archived runtime/UI/AudioEngine

The archived `MainActivity`, `AncRuntime` and `AudioEngine` are not copied wholesale over the active
versions because doing so would remove the SAF storage model, predictive Headphones controller,
live graph and current diagnostic recorder. Their vehicle concepts are being ported incrementally.

## Background and audio coexistence contract

While ANC is running:

1. A foreground service is declared as `mediaPlayback|microphone`.
2. A partial CPU wake lock keeps the DSP/audio thread runnable with the display off.
3. `android:stopWithTask="false"` means dismissing the UI task is not a stop command.
4. The service is ongoing and `START_STICKY`; an explicit Stop action stops audio.
5. ANC Lab does not call `requestAudioFocus` or request exclusive/transient/ducking focus.
6. Normal media `AudioTrack` output is used so Android may mix it with another app.

Android can still terminate a process under exceptional pressure or after Force stop, and an OEM
head unit can still impose routing/source policy. A process restart cannot safely recreate an
in-progress adaptive controller from nothing, so the service must not falsely report resumed ANC
unless the audio engine itself is active.

## Next vehicle-side porting targets

The remaining high-value, non-superseded vehicle work is to wire the restored telemetry,
mechanical-frequency, latency/recipe and narrowband controller classes into the current SAF-backed
runtime and UI without changing the active Headphones path.
