# Signal and control design

This document reconciles the supplied ANC Lab v0.5 algorithm notes with the later Headphones
requirements. Unless explicitly marked **Headphones**, the recovered narrowband sections describe
the P38/E46 vehicle controller.

## Audio path and coexistence

ANC Lab opens a full-duplex microphone/output path, requests Android's `UNPROCESSED` input where
available, and applies selected devices with `setPreferredDevice`. Actual routing remains subject to
Android/OEM policy.

The ANC stream **does not request Android audio focus**. There is no ordinary, transient, ducking or
exclusive focus request, so ANC Lab does not instruct Android to suppress another player. Final
mixing and head-unit routing remain device policy.

A microphone/media foreground service and partial CPU wake lock keep a running engine alive while
the Activity is backgrounded or the display is off. Dismissing the UI task is not a stop command.

## Vehicle analysis path

The recovered P38/E46 analysis design uses a full-rate microphone RMS path plus a separate analysis
copy with DC blocking and an eighth-order 210 Hz Butterworth low-pass. The analysis copy is heavily
decimated and held in a short ring buffer. FFT scans discover broad 8–200 Hz candidates while exact
frequency complex correlation is used for cancellation coefficients.

## Vehicle click-resistant synthesis

Each narrowband target uses a phase-continuous oscillator:

```text
y[n] = Σ Re{ Ck · exp(jφk[n]) }
φk[n+1] = φk[n] + 2πfk[n]/Fs
```

Gain/phase and frequency changes glide rather than restarting carrier phase. Combined lane output is
bounded by a single digital ceiling.

## Vehicle mechanical models

Each editable target stores measured frequency `fref`, detected/telemetry anchor `xref` and source:

```text
fpredicted = fref × xcurrent / xref
```

Sources include best speed, GPS speed, OBD speed, RPM, engine load, throttle and fixed frequency.
A quiet/agile two-model tracker blends estimates to reject jitter while following genuine order
movement.

## Vehicle automatic path estimate

For a narrowband lane the selected microphone residual is modelled as:

```text
E = D + H·C
```

with disturbance `D`, secondary path `H`, and generated complex coefficient `C`. Equal and opposite
probes estimate both disturbance and path:

```text
H ≈ (E+ − E−) / (2·Cprobe)
D ≈ (E+ + E−) / 2
Copt = −D / H
```

A half-strength candidate and then full candidate are acoustically validated. Worsening candidates
are rolled back. Once running, bounded complex filtered-X normalized adaptation uses:

```text
C[n+1] = leak·C[n] − μ·conj(H)·E[n] / (|H|² + ε)
```

Repeated rejected updates force a new muted baseline and path measurement.

## Vehicle coherent multi-line mode

The recovered source's "Experimental broadband" mode is actually a bank of independently verified
coherent 8–200 Hz spectral lines, not causal arbitrary broadband waveform cancellation. Candidates
must repeat across scans before admission. A second safety layer watches filtered whole-band energy;
repeated increases of more than 1.5 dB trip all lanes and impose an eight-second monitoring-only
cooldown. The device-level lane cap is 24.

## Headphones: predictive broadband override

The above vehicle controller does **not** define current Headphones mode.

During normal IEM use the phone microphone cannot hear the cancellation result at the ear. It is
therefore a feed-forward **reference microphone**, not an error microphone. The active Headphones
model is:

```text
x[n]                  = measured external reference
x_hat[n + H]          = P(z) x[n]                 # direct-horizon prediction
y[n]                  = W(z) x_hat[n + H]         # cancellation drive
y_ear_hat[n + H]      = S_hat(z) y[n]
e_ear_hat[n + H]      = x_hat[n + H] + y_ear_hat[n + H]
```

Where:

- `P(z)` is a 128-tap adaptive direct-horizon predictor;
- `W(z)` is a 128-tap cancellation controller;
- `S_hat(z)` is the stored measured 128-tap headphone secondary-path FIR;
- `H` is derived from the stored calibration's measured output delay rather than hard-coded;
- the controller is seeded with a conservative regularised inverse of `S_hat(z)` and then refined;
- prediction confidence gates low-skill/unpredictable content.

The graph therefore distinguishes measured reference input from modelled ear-level cancellation and
modelled residual. There is no independent in-ear residual measurement unless future hardware
provides an ear microphone.

The Headphones calibration itself is a separate bench state: headphones are unworn and placed beside
the phone microphone so the application can measure bulk route delay and the secondary-path FIR.

## Causality limit

Prediction does not repeal causality. If the output route has tens of milliseconds of latency, only
components whose future value can be predicted across that horizon are plausible cancellation
targets. Persistent tones, strongly correlated low-frequency machinery/road structure and other
repeatable content are more promising than genuinely stochastic broadband noise.

## Profiles, recipes and logs

P38, E46 and Headphones remain logically independent. Current storage keeps all of them beneath the
single SAF-selected `Documents/ANC` tree. Per-profile latency and recipe histories are retained while
legacy shared files remain for compatibility with earlier reconstruction builds.

Headphones diagnostic session WAVs contain microphone reference plus generated cancellation drive.
Vehicle logging/recipe concepts from the recovered source are retained for incremental porting.

## Algorithm references

See `../THIRD_PARTY_NOTICES.md`. ANC Lab's Java implementations are original; the listed projects
are algorithmic references and acknowledgements rather than redistributed source.
