#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <array>
#include <cstdint>
#include <memory>
#include <string>

#include "../dsp/Oscillator.h"
#include "../dsp/FxLmsFilter.h"
#include "../dsp/SecondaryPathModel.h"

namespace anc::audio {

struct CalibrationData {
    bool success = false;
    int32_t delaySamples = 0;
    float pathGain = 1.0f;
    float quality = 0.0f;
    int32_t sampleRate = 0;
    int32_t outputDeviceId = 0;
    int32_t inputDeviceId = 0;
};

struct DiagnosticsData {
    bool running = false;
    int32_t sampleRate = 0;
    int32_t performanceMode = 0;
    int32_t sharingMode = 0;
    int32_t framesPerBurst = 0;
    int32_t xRunCount = 0;
    int32_t outputDeviceId = 0;
    int32_t inputDeviceId = 0;
    float inputRms = 0.0f;
    float outputRms = 0.0f;
    float calibrationDelayMs = 0.0f;
    float calibrationQuality = 0.0f;
};

class AncEngine : public oboe::FullDuplexStream, public oboe::AudioStreamErrorCallback {
public:
    explicit AncEngine(int32_t preferredSampleRate);
    ~AncEngine() override;

    AncEngine(const AncEngine &) = delete;
    AncEngine &operator=(const AncEngine &) = delete;

    oboe::Result start() override;
    oboe::Result stop() override;
    void setTargetFrequency(float frequencyHz) noexcept;
    void updateParameters(float gainPercent, float phaseDegrees) noexcept;

    void beginCalibration() noexcept;
    CalibrationData finishCalibration();
    void applyCalibration(int32_t delaySamples, float pathGain, float quality) noexcept;
    DiagnosticsData diagnostics() const noexcept;

    oboe::DataCallbackResult onBothStreamsReady(
            const void *inputData, int numInputFrames,
            void *outputData, int numOutputFrames) override;

    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    static constexpr int kReferenceTaps = 2;
    static constexpr int kSecondaryPathTaps = 512;
    static constexpr int kCalibrationProbeFrames = 511;
    static constexpr int kCalibrationCaptureFrames = 48000;
    static constexpr int kCalibrationProbeStart = 4096;
    static constexpr float kCalibrationProbeLevel = 0.08f;

    const int32_t mPreferredSampleRate;
    int32_t mActualSampleRate = 0;

    std::shared_ptr<oboe::AudioStream> mOutputStream;
    std::shared_ptr<oboe::AudioStream> mInputStream;

    dsp::Oscillator mReferenceOscillator;
    dsp::FxLmsFilter<kReferenceTaps> mAdaptiveFilter;
    dsp::SecondaryPathModel<kSecondaryPathTaps> mSecondaryPathCos;
    dsp::SecondaryPathModel<kSecondaryPathTaps> mSecondaryPathSin;

    std::atomic<float> mGainCoefficient{0.5f};
    std::atomic<float> mPhaseOffsetRad{0.0f};
    std::atomic<float> mTargetFrequencyHz{0.0f};
    std::atomic<bool> mIsRunning{false};

    std::atomic<bool> mCalibrating{false};
    std::atomic<int32_t> mCalibrationFrame{0};
    std::array<float, kCalibrationProbeFrames> mCalibrationProbe{};
    std::array<float, kCalibrationCaptureFrames> mCalibrationInput{};
    std::atomic<int32_t> mCapturedCalibrationFrames{0};

    std::atomic<int32_t> mCalibratedDelaySamples{0};
    std::atomic<float> mCalibratedPathGain{1.0f};
    std::atomic<float> mCalibrationQuality{0.0f};
    std::atomic<float> mInputRms{0.0f};
    std::atomic<float> mOutputRms{0.0f};

    void buildCalibrationProbe() noexcept;
    void updateSecondaryPathModel() noexcept;
};

} // namespace anc::audio
