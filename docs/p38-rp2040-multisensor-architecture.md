# P38 RP2040 multisensor ANC architecture

## Planned acquisition topology

The RP2040 is the authoritative input clock and USB acquisition hub.

- ADXL345 front / transmission tunnel: SPI, separate chip-select.
- ADXL345 rear: same SPI bus, separate chip-select.
- Mic 1 reference L / Mic 2 reference R / Mic 3 error L / Mic 4 error R: TLV320ADC5140 four-channel ADC over TDM.
- RP2040 packages microphone samples, accelerometer observations and a monotonic source sample counter into one USB stream to Android.

Android receive time is retained for diagnostics, but relative sensor timing must use the RP2040 source counter so USB packet scheduling does not appear as physical sensor jitter.

## Output is a separate latency domain

For phone/tablet testing, cancellation output may use AUX, wired Android Auto, wireless Android Auto, a direct USB DAC, or another route. Each route must have its own calibration profile.

Output calibration measures the complete command-to-error-microphone response: Android scheduling/mixer, route transport, head-unit DSP, amplifier/speaker response and acoustic propagation. Repeated probes produce median delay, p95 jitter and confidence. Route identity should include device, connection method, head-unit input mode, volume, EQ/DSP state and other settings that may change delay or transfer response.

A long but stable route may remain useful for periodic/narrowband prediction. Low delay alone is not sufficient: timing variation destroys phase repeatability.

## Preview budget

For each upstream reference, representative road/vibration events should be cross-correlated against both B-pillar error microphones on the RP2040 clock to measure reference lead and its variation.

True feed-forward broadband cancellation requires the upstream reference to lead the error signal by more than the total output path delay plus a jitter reserve. If that margin is negative, stochastic broadband feed-forward is disabled for that reference/route combination.

Periodic components are different. Stable engine, prop-shaft, tyre-order or structure-borne tones can be predicted forward in phase through a long deterministic route, provided the reference and output clocks remain sufficiently repeatable. The app therefore treats broadband-feedforward eligibility and periodic-prediction eligibility separately rather than imposing a fixed 200 Hz cutoff.

## Stereo secondary path

Calibration retains the full 2x2 command-to-error matrix:

- L output -> L error mic
- L output -> R error mic
- R output -> L error mic
- R output -> R error mic

Decorrelated left/right low-level probes are required so crosstalk is identified rather than folded into two independent mono models.

The sensor registry remains extensible to extra C-pillar error microphones, rear wheel-arch/floor accelerometers, seat-rail/floor-pan accelerometers, and additional acoustic references. Structural accelerometers are preferred near exposed wheel-arch locations where practical.

## Controller priority

Current narrowband/feedback/headphone programs remain independently usable and continue learning from sensor logs. SENSOR_PRIMARY is enabled only after both sided acoustic reference/error paths and both output routes are connected, synchronized, calibrated and repeatable. Legacy programs then become secondary/fallback controllers.

## Hardware adapter still required

The current Android branch contains the topology, calibration math, timing budget, persistence, settings UI and learning bus. It does not yet contain the RP2040 firmware or Android USB parser for the final packet format, nor the final multichannel/MIMO output engine. Those components should preserve the RP2040 source sample counter end-to-end.
