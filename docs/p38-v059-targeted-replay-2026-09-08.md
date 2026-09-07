# P38 ANC Lab v0.5.9 targeted replay — 2026-09-08

## Basis

This change set follows the completed v0.5.8.1 full-WAV controller replay and the subsequent targeted analysis of the 20–200 second section of the same P38 source recording. The full replay established that controller stability, collision ownership and warm secondary-path reuse were substantially improved, but also exposed an acquisition bottleneck: the six microphone-discovery slots could be occupied before the dominant ~34.4 Hz prop-shaft region strengthened.

The source WAV replay uses a synthetic unity-gain 50 ms speaker-to-microphone path. These results validate controller/orchestration behaviour and do **not** establish real cabin attenuation.

## Accepted v0.5.9 changes

- Persistent discovery candidates remain eligible on later scans rather than being forgotten after first maturity.
- With speculative broadband ANC off, the microphone-discovered fallback bank can use up to 10 slots. Broadband-on mode retains a six-slot reserve.
- Existing active or calibrating lanes are protected. Replacement is restricted to genuinely IDLE/rejected zero-output lanes after the normal retry delay, and requires a materially stronger candidate.
- A fallback candidate must reach -62 dBFS to consume a cancellation controller. Monitoring/logging remains 8–200 Hz and is not subject to this admission floor.
- Vehicle broadband now models the loudspeaker return through the same 15–600 Hz observation path as the microphone. Predictable-tone notches are applied only after reconstruction of the disturbance.
- Predictive headphone secondary-path and filtered-X modelling receive the corresponding observation-path correction.

## Targeted replay result carried forward

The selected pre-continuity candidate recovered the previously missed prop-shaft region and increased warm productive cancellation from 342.3 to 354.5 lane-seconds (+3.6%). The prop-shaft band contributed 43.8 seconds of productive lane time in the targeted synthetic-path replay.

A later continuity-gate experiment reduced recalibration activity but also removed most of the recovered prop-shaft coverage. That experiment is intentionally **not** part of v0.5.9.

## Safety invariants

- Existing total/output ceilings are unchanged.
- Frequencies below the verified vehicle speaker cancellation minimum remain monitor-only by default.
- Enabling broadband does not forcibly discard a lane that is already producing useful narrowband cancellation; inactive excess lanes are trimmed first and active excess lanes age out naturally.
- Real acoustic benefit and safe output remain subject to measured in-car route calibration and closed-loop verification.
