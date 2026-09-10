# Latest output-route review — 2026-09-10

## Recordings

- `session-20260910-092219.wav` — 158.732 seconds; essentially the complete four-pair ANC OFF/ON test.
- `session-20260910-092632.wav` — 110.984 seconds; stopped during the third ON interval.

Both files are stereo float WAVs written by ANC Lab: channel 1 is the microphone input and channel 2 is the app's generated anti-noise command.

## Findings

The output route was active in both recordings. Channel 2 contains substantial commands at approximately 24–25, 35, 44–45, 49–50, 54–58, 82 and 120 Hz. The controller also inserted its own brief muted verification periods inside some nominal ON intervals, as expected.

The microphone channel remained dominated by a line near 35 Hz. At that frequency, the active and muted levels differed by only about 0.1 dB in the more complete run, which is below a useful or reliable cancellation result. This is consistent with a phone speaker having negligible acoustic authority in the 20–40 Hz region.

At 120 Hz, parts of the active intervals were several decibels lower than adjacent muted intervals. The changing source and non-simultaneous comparisons mean this is not yet proof of cancellation efficacy, but it does show that the route is frequency-dependent rather than simply disconnected.

## Implemented response

Version 0.5.9.7 adds a two-polarity, two-pass stepped-sine sweep at 20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160 and 200 Hz. Phase-reversed measurements separate repeatable output response from steady ambient tones. The result is stored against the physical output, physical microphone and media-volume setting.

Once a sweep exists, unsupported frequencies remain visible and monitored but cannot emit narrowband anti-noise. Broadband mode is blocked unless most of the measured band is supported. A stored sweep is rejected when the microphone or media-volume setting changes materially.

The microphone benchmark is now a persistent two-step workflow. The calibrated reference capture survives input switching and app recreation for ten minutes, and either step starts independent SPL capture automatically. Frequency-response correction remains separate from the scalar absolute-SPL offset.
