# Algorithm references and acknowledgements

ANC Lab contains original Java implementations informed by the following open-source research
and demonstration projects. No source files from these projects are bundled in the APK.

- [AhSilence](https://github.com/AhmadHassan-BTed/AhSilence), Apache License 2.0 — Android
  foreground execution, phase-continuous tone synthesis and radix-2 FFT architecture.
- [ANC-Implementation](https://github.com/iancraz/ANC-Implementation), MIT License — periodic
  narrowband reference synthesis, independent harmonic control, secondary-path modelling and the
  causality limits of broadband phone-based ANC.
- [adafilt](https://github.com/fhchl/adafilt), MIT License — normalized/leaky LMS and filtered-X
  adaptive-filter structure.
- [IMM-KF adaptive ANC for hearables](https://github.com/yesinali/IMM-KF-adaptive-ANC-for-hearables),
  MIT License — innovation-weighted blending of quiet and agile adaptive models. ANC Lab applies
  this principle to lightweight frequency/order tracking; it does not embed that project's
  simulated FIR controller.
- [aurras](https://github.com/matthewcaren/aurras), MIT License — DC blocking, anti-aliasing and
  explicit measurement of the acoustic environment/secondary path before cancellation.

The repository license terms remain those of their respective authors. These links and notices
are provided for reproducibility and acknowledgement, not because their code is redistributed.
