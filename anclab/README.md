# ANC Lab v0.5.0 reconstruction

This module combines the supplied ANC Lab APK reconstruction with the later user-supplied
`ANC-Lab-v0.5.0-source-and-documentation.zip`. Where that source conflicts with decisions made in
the reconstruction chat, the chat-established design is authoritative.

## Active Headphones design

Headphones is deliberately different from the recovered vehicle controller. During normal IEM use
the phone microphone is an **external reference microphone only**; it cannot hear the residual at
the user's ear. The active controller therefore does not pretend that the phone mic is a physical
error microphone.

- 128-tap direct-horizon predictor.
- 128-tap feed-forward FxNLMS cancellation controller.
- 128-tap measured headphone secondary-path FIR.
- Prediction horizon derived from the selected stored headphone calibration, including its measured
  bulk output delay; the calibrated path is not replaced by a hard-coded delay.
- A conservative regularised inverse of the measured secondary path seeds the controller so a
  low-gain calibration does not leave the output stuck below the PCM quantisation threshold.
- Prediction confidence gates unpredictable content instead of emitting arbitrary anti-noise.
- The live graph page shows measured reference input, cancellation drive, modelled cancellation at
  the ear, and modelled/predicted residual. Ear-level traces are explicitly predictions, not
  independent measurements.
- Bench calibration still places the headphones beside the phone microphone while they are unworn.

## Recovered vehicle-side background

The supplied v0.5 source adds substantial vehicle context which does not conflict with the current
Headphones path. The branch now retains/restores the reusable core for:

- logical P38, E46 and Headphones profiles;
- P38 speed/RPM mechanical-order defaults and an initially empty E46 mechanical model set;
- editable speed/RPM/load/throttle/fixed mechanical-frequency models;
- GPS speed and generic paired Bluetooth ELM327 OBD-II telemetry support;
- two-model adaptive frequency tracking;
- DC blocking, eighth-order 210 Hz analysis filtering, spectral analysis and radix-2 peak discovery;
- balanced positive/negative secondary-path probes;
- bounded narrowband complex filtered-X adaptation with validation/rollback;
- coherent multi-line detector and whole-band safety guard;
- REW/miniDSP microphone calibration parsing and SPL statistics;
- the recovered algorithm acknowledgements in `THIRD_PARTY_NOTICES.md`.

The old full vehicle `MainActivity`, `AncRuntime`, `AudioEngine`, recording implementation and
Android Auto `MediaBrowserService` are **not** dropped over the current app wholesale. They conflict
with the current SAF storage, predictive Headphones engine, stereo diagnostic recording and live
graph. Their behaviour is documented in `docs/BACKGROUND_MERGE.md` so the vehicle UI/runtime can be
ported deliberately rather than regressing the working Headphones path.

## Background operation and music coexistence

ANC is owned by a foreground service declared for both microphone capture and media playback. The
service holds a partial CPU wake lock, is not stopped when the Activity task is dismissed, and
returns `START_STICKY` while active. The screen may therefore turn off and other apps may be used
while the ANC audio loop continues.

ANC Lab **does not request Android audio focus**—not ordinary, transient, exclusive or ducking
focus. It therefore does not ask Android to pause, duck or suppress Tidal, Music Assistant, another
music player, or other media. The generated `AudioTrack` uses normal media attributes so Android
can mix it with another app where the device/head unit supports mixing.

There is still an unavoidable platform boundary: an OEM/head unit may impose its own source
switching, USB routing or capture policy. The recovered Android Auto media-browser surface is not
currently activated because presenting ANC Lab as the selected media source could itself prompt a
head unit to switch away from another player even though ANC Lab does not request focus.

## User-visible storage

On first run choose **Internal storage → Documents → ANC** in Android's folder picker and grant the
folder. Android's Storage Access Framework permission is persisted.

`Documents/ANC` remains the single user-visible source of truth for **all** sources/profiles:

```text
Documents/ANC/
  README-storage.txt
  profiles/
    current_profile.json
    mechanical_frequencies.json          # legacy/shared compatibility
    latency_profiles.jsonl               # legacy/shared compatibility
    headphones/
      profile.json
      calibration.json
      test_frequencies.json
      latency_profiles.jsonl
    p38/
      profile.json
      mechanical_frequencies.json
      latency_profiles.jsonl
    e46/
      profile.json
      mechanical_frequencies.json
      latency_profiles.jsonl
  calibration/
  logs/
    app.log
    monitoring-*.csv
    session-*.csv
  wav/
    calibration-probe-*.wav
    calibration-response-*.wav
    session-*.wav
  recipes/
    cancellation_recipes.jsonl           # legacy/shared compatibility
    headphones.jsonl
    p38.jsonl
    e46.jsonl
  exports/
```

Long-running WAV/CSV streams may be written to private cache while active for real-time safety;
completed files are copied into `Documents/ANC` and the temporary copy is removed.

## Deliberate limits

This is an acoustics experiment, not safety-certified ANC or a certified sound-level meter.
Predicting approximately 80 ms or more into the future cannot make genuinely stochastic broadband
noise causal. The predictive Headphones mode is expected to work best on repeatable or strongly
correlated broadband content and persistent low-frequency structure. The phone microphone also
does not measure the in-ear residual during normal use, so predicted cancellation must not be
presented as measured attenuation.

## Build

```bash
./gradlew :anclab:testDebugUnitTest :anclab:assembleDebug
```

GitHub Actions uploads `anclab-debug.apk` as `ANC-Lab-v0.5.0-rebuild-debug`.
