# ANC Lab v0.5.0 reconstruction

This module is a source reconstruction of the supplied `ANC-Lab-v0.4.0-debug.apk` (`com.p38.anclab`). It preserves the P38/Headphones profile concepts found in that APK and replaces the Headphones cancellation path with an experimental feedback **Filtered-x Normalized LMS (FxNLMS)** controller.

## Headphones profile

- Loads a stored headphone calibration automatically when one exists.
- Manual re-calibration remains available.
- Calibration plays a low-level pseudo-random probe and estimates output-to-microphone delay plus a 128-tap secondary-path FIR.
- The measured path is saved in `Documents/ANC/profiles/headphones/calibration.json`.
- Calibration probe/response WAVs are saved under `Documents/ANC/wav/`.
- Runtime input/output WAV + CSV logging and background monitoring logs are mirrored to `Documents/ANC`.

The Headphones controller is a single-error-microphone feedback FxNLMS implementation. Useful cancellation still depends on microphone placement and very low end-to-end latency; generic Bluetooth A2DP latency can make broadband ANC ineffective even when calibration succeeds.

## User-visible storage

On first run, choose **Internal storage → Documents → ANC** in Android's folder picker and tap **Use this folder**. The persistent Storage Access Framework permission is then reused.

`Documents/ANC` is the user-visible source of truth:

```text
Documents/ANC/
  README-storage.txt
  profiles/
    current_profile.json
    mechanical_frequencies.json
    latency_profiles.jsonl
    headphones/
      profile.json
      calibration.json
    p38/
      profile.json
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
    cancellation_recipes.jsonl
  exports/
```

The app can use temporary private cache files while a WAV/CSV stream is actively being written, but completed files are copied into the selected `Documents/ANC` tree and the cache copy is removed.

## Build

```bash
./gradlew :anclab:assembleDebug
```

GitHub Actions uploads `anclab-debug.apk` as `ANC-Lab-v0.5.0-rebuild-debug`.
