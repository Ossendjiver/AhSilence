# ANC Lab v0.5.9 recovery audit — 8 September 2026

## Scope

The recovery branch starts from `0.5.9.1-rebuild` (`4146ff5`) rather than from a v0.6 tag. The audit covered the complete v0.6 history through `0.6.12-fixed-tone-suite-dev` (`0e9e88d`), the six steady-tone WAVs and matching session logs under the ANC sandbox, and 22 saved latency probe/response pairs.

The last available steady recording is `session-20260908-221856.wav`; it is the stated reinstalled-v0.5.8 control. The screenshot showed `221836`, but no file with that timestamp exists in the supplied folder.

## What the recordings establish

The 120 Hz results below compare the microphone target magnitude with the nearest muted observation within three seconds. Positive figures mean reduction; negative figures mean the microphone became louder. The median is deliberately reported rather than the single best transient.

| Session | Build/mode evidence | Reported best | Independent median change | Best sustained 3 s | Worst increase | False-certified time |
|---|---|---:|---:|---:|---:|---:|
| 20:50:00 | v0.6.11-style drifting 120 Hz lab | 5.38 dB | +0.048 dB | +6.068 dB | 4.989 dB | 3.75 s |
| 21:24:16 | v0.6.12 fixed 120 Hz lab | 4.57 dB | -0.039 dB | +3.591 dB | 13.803 dB | 10.50 s |
| 21:48:02 | v0.6.12 fixed 120 Hz lab | 13.35 dB | +0.071 dB | +2.630 dB | 5.305 dB | 5.00 s |
| 21:52:27 | v0.6 Room mode | no hold | +0.046 dB | +0.069 dB | 2.025 dB | 0 s |
| 22:18:56 | reinstalled v0.5.8 control | no hold | +0.015 dB | +4.017 dB | 8.054 dB | 0 s |

The 65 Hz session at 21:43:03 similarly reported a 1.67 dB hold, while the independent median change was +0.014 dB, best sustained three-second reduction was +0.732 dB and the worst increase was 10.951 dB.

The brute-force lab therefore demonstrated that the physical setup can produce short reductions and can keep its oscillator on 120 Hz. It did not demonstrate sustained cancellation: several states certified as successful were independently louder, and median attenuation was effectively zero.

## Root cause

The five latest usable Bluetooth/UMIK calibration pairs measured 452.000, 436.542, 429.583, 412.958 and 439.979 ms of output-to-microphone delay. Their correlation quality was only 0.087–0.230, so each candidate must be evaluated over a fully fresh, adjacent observation rather than against a distant baseline.

The old v0.5.9 controller waited a fixed 650 ms but measured a trailing 750 ms phasor. With a representative 430 ms route, only about 220 ms of the analysis window contained the new acoustic command; roughly 530 ms still represented the preceding state. The v0.6 fixed-tone lab was more severely mismatched: its 200 ms drive settle and 300 ms mute settle were shorter than the measured transport delay itself.

This explains both reported symptoms: v0.5.9 repeatedly tried to form a secondary-path estimate but could not lock, while v0.6's direct phase search appeared to lock quickly but often certified a transient or the preceding command.

## Changes retained from v0.6

- Local spectral prominence, with persistence, is useful for discovering a real line that is quiet in absolute dBFS.
- 32-bit float diagnostic WAVs preserve low-level anti-noise that the v0.5.8 PCM16 recording quantised.
- Exact digital-silence detection prevents long misleading sessions on a dead Android input.
- More complete session diagnostics are essential for separating detection, calibration, verification and running behaviour.

These parts were reworked rather than copied verbatim. The prominence path uses a fixed 33-value sampled median with no per-peak allocation and a 4096-point transform at the 500 Hz analysis rate. A candidate must still exceed -82 dBFS at the bank boundary and 10 dB local prominence unless it already meets the original strong-tone threshold; the detector has an additional -86 dBFS sanity floor.

## Changes deliberately not retained

- The v0.6 Room/direct-error controller and no-blind-probe variants: the 21:52:27 run spent almost the entire recording at `3 found · 0 cancelling` and produced no sustained 120 Hz reduction.
- v0.6.11 frequency drift during a fixed-tone test: a fixed test must remain fixed.
- v0.6.12's one-muted-baseline, one-candidate verifier and 200/300 ms settles: they are incompatible with the measured route and produced false holds.
- The -110/-112 dBFS discovery/lock thresholds: they allow numerical or environmental floor structure to consume a controller lane.
- Uncalibrated multisensor takeover: the architecture can be revisited when synchronized physical reference sensors exist, but it cannot improve the current single-error-microphone bench setup.

## Recovery controller

Each controller now receives the active route's stored delay. Its coherent target window is six cycles bounded to 300–750 ms, and every changed command waits for route delay + that window + 100 ms acoustic guard (with a 650 ms minimum and 3000 ms cap). At 120 Hz on the measured 430 ms route this is 830 ms; at 8 Hz it is 1280 ms.

After the existing positive/negative secondary-path probe, half-strength check and full-level check, the controller mutes again, obtains a fresh baseline, replays the exact selected command, and requires at least 0.75 dB repeatable reduction. Only then can it enter `RUNNING` or contribute a saved secondary-path recipe.

## Targeted recovery validation

Production discovery replay (low-pass, 96:1 decimation, FFT peak finder, persistence tracker and admission logic) produced:

| Recording excerpt | Raw 120 Hz hits | Mature candidate coverage | First mature | Mean detected frequency | Mean level | Mean prominence |
|---|---:|---:|---:|---:|---:|---:|
| 20:50:00, first 20 s | 39/39 (100%) | 37/39 (94.87%) | 1.6 s | 120.006 Hz | -57.40 dBFS | 30.27 dB |
| 21:48:02, first 12 s | 23/23 (100%) | 21/23 (91.30%) | 1.6 s | 119.980 Hz | -58.20 dBFS | 33.90 dB |
| 22:18:56, first 12 s | 23/23 (100%) | 21/23 (91.30%) | 1.6 s | 120.009 Hz | -65.68 dBFS | 21.54 dB |

The target is therefore not being lost by discovery in these recordings. The principal failure was the route-misaligned path/verification sequence.

A deterministic 120 Hz closed-loop unit test models the measured 430 ms transport delay, the 300 ms trailing observation window, a complex secondary path and a stationary disturbance. It reaches the new paired-confirmed `RUNNING` state with more than 6 dB modelled reduction. This validates controller timing and state logic only.

## Recovered application workflow

The recovery build also restores the operational pieces needed to collect the next useful live result: separate editable P38, E46 and headphone profiles; persistent route and microphone calibrations; independent SPL operation; foreground screen-off monitoring; selectable OBD2 telemetry; continuous monitoring logs; Start Log/ANC lifecycle coupling; and Android Auto media controls. Input/output refreshes retain a connected selection by stable route name. These changes do not alter the conservative controller admission or output limits.

The complete JVM-testable suite now contains 35 passing tests, including SPL energy weighting, microphone-calibration parsing/benchmark offsets, delayed closed-loop convergence, route-timing and saved secondary-path replay regressions.

## Limit of the dry evidence

An existing WAV cannot be used to hear the recovered controller's newly generated anti-noise through the real Bluetooth speaker and room path: its microphone channel already contains the acoustic result of the old app's output. The next decisive step is a live closed-loop run with the Bluetooth speaker and USB microphone, preserving the same physical placement and volume. The app can then learn phase/gain/timing iteratively and save the verified route-specific secondary path. Bluetooth jitter still makes high-frequency broadband cancellation unsuitable; the test should begin with stable narrowband tones and compare repeated adjacent mute/output windows.
