#include "AncEngine.h"

#include <algorithm>
#include <android/log.h>
#include <cmath>

#define TAG "AhSilence-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace anc::audio {

namespace {
constexpr float kDegreesToRadians = 3.14159265358979323846f / 180.0f;
}

AncEngine::AncEngine(int32_t preferredSampleRate)
        : mPreferredSampleRate(preferredSampleRate) {
    buildCalibrationProbe();
}

AncEngine::~AncEngine() { stop(); }

void AncEngine::buildCalibrationProbe() noexcept {
    uint32_t lfsr = 0x1FFu;
    for (auto &sample : mCalibrationProbe) {
        const uint32_t bit = ((lfsr >> 0u) ^ (lfsr >> 4u)) & 1u;
        lfsr = (lfsr >> 1u) | (bit << 8u);
        sample = (lfsr & 1u) ? kCalibrationProbeLevel : -kCalibrationProbeLevel;
    }
}

oboe::Result AncEngine::start() {
    if (mIsRunning.load(std::memory_order_relaxed)) return oboe::Result::OK;

    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(mPreferredSampleRate)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    oboe::Result result = outBuilder.openStream(mOutputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        return result;
    }
    setOutputStream(mOutputStream.get());
    mActualSampleRate = mOutputStream->getSampleRate();

    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(mActualSampleRate)
            ->setBufferCapacityInFrames(mOutputStream->getBufferCapacityInFrames() * 2);

    result = inBuilder.openStream(mInputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        mOutputStream->close();
        mOutputStream.reset();
        return result;
    }
    setInputStream(mInputStream.get());

    mReferenceOscillator.setFrequency(mTargetFrequencyHz.load(), static_cast<float>(mActualSampleRate));
    updateSecondaryPathModel();
    mIsRunning.store(true, std::memory_order_relaxed);
    result = oboe::FullDuplexStream::start();
    if (result != oboe::Result::OK) {
        mIsRunning.store(false, std::memory_order_relaxed);
        LOGE("Failed to start duplex streams: %s", oboe::convertToText(result));
        return result;
    }
    LOGI("ANC engine started @ %d Hz", mActualSampleRate);
    return oboe::Result::OK;
}

oboe::Result AncEngine::stop() {
    mCalibrating.store(false, std::memory_order_relaxed);
    mIsRunning.store(false, std::memory_order_relaxed);
    oboe::Result result = oboe::FullDuplexStream::stop();
    if (mOutputStream) { mOutputStream->close(); mOutputStream.reset(); }
    if (mInputStream) { mInputStream->close(); mInputStream.reset(); }
    return result;
}

void AncEngine::setTargetFrequency(float frequencyHz) noexcept {
    mTargetFrequencyHz.store(frequencyHz, std::memory_order_relaxed);
    updateSecondaryPathModel();
}

void AncEngine::updateParameters(float gainPercent, float phaseDegrees) noexcept {
    mGainCoefficient.store(std::clamp(gainPercent, 0.0f, 100.0f) / 100.0f);
    mPhaseOffsetRad.store(phaseDegrees * kDegreesToRadians);
}

void AncEngine::beginCalibration() noexcept {
    mCalibrationInput.fill(0.0f);
    mCalibrationFrame.store(0);
    mCapturedCalibrationFrames.store(0);
    mCalibrating.store(true);
    LOGI("Calibration probe started");
}

CalibrationData AncEngine::finishCalibration() {
    mCalibrating.store(false);
    CalibrationData result{};
    result.sampleRate = mActualSampleRate;
    result.outputDeviceId = mOutputStream ? mOutputStream->getDeviceId() : 0;
    result.inputDeviceId = mInputStream ? mInputStream->getDeviceId() : 0;

    const int captured = std::min(mCapturedCalibrationFrames.load(), kCalibrationCaptureFrames);
    if (captured <= kCalibrationProbeStart + kCalibrationProbeFrames) return result;

    float probeEnergy = 0.0f;
    for (float v : mCalibrationProbe) probeEnergy += v * v;

    float bestAbsCorr = 0.0f;
    float bestSignedGain = 1.0f;
    int bestLag = -1;

    for (int lag = kCalibrationProbeStart; lag + kCalibrationProbeFrames < captured; ++lag) {
        float dot = 0.0f;
        float inputEnergy = 1e-12f;
        for (int i = 0; i < kCalibrationProbeFrames; ++i) {
            const float x = mCalibrationInput[lag + i];
            dot += mCalibrationProbe[i] * x;
            inputEnergy += x * x;
        }
        const float corr = dot / std::sqrt(probeEnergy * inputEnergy);
        const float absCorr = std::abs(corr);
        if (absCorr > bestAbsCorr) {
            bestAbsCorr = absCorr;
            bestLag = lag;
            bestSignedGain = dot / probeEnergy;
        }
    }

    if (bestLag >= 0) {
        result.delaySamples = std::max(0, bestLag - kCalibrationProbeStart);
        result.pathGain = bestSignedGain;
        result.quality = bestAbsCorr;
        result.success = bestAbsCorr >= 0.08f;
        if (result.success) applyCalibration(result.delaySamples, result.pathGain, result.quality);
    }

    LOGI("Calibration complete success=%d delay=%d quality=%.3f gain=%.3f",
         result.success, result.delaySamples, result.quality, result.pathGain);
    return result;
}

void AncEngine::applyCalibration(int32_t delaySamples, float pathGain, float quality) noexcept {
    mCalibratedDelaySamples.store(std::max(0, delaySamples));
    mCalibratedPathGain.store(pathGain);
    mCalibrationQuality.store(quality);
    updateSecondaryPathModel();
}

void AncEngine::updateSecondaryPathModel() noexcept {
    const float freq = mTargetFrequencyHz.load();
    int effectiveDelay = mCalibratedDelaySamples.load();
    if (freq > 1.0f && mActualSampleRate > 0) {
        const float periodSamples = static_cast<float>(mActualSampleRate) / freq;
        if (periodSamples > 1.0f) effectiveDelay = static_cast<int>(std::fmod(static_cast<float>(effectiveDelay), periodSamples));
    }
    effectiveDelay = std::clamp(effectiveDelay, 0, kSecondaryPathTaps - 1);
    float gain = mCalibratedPathGain.load();
    if (std::abs(gain) < 0.02f) gain = gain < 0 ? -0.02f : 0.02f;
    mSecondaryPathCos.setPureDelay(static_cast<std::size_t>(effectiveDelay), gain);
    mSecondaryPathSin.setPureDelay(static_cast<std::size_t>(effectiveDelay), gain);
}

DiagnosticsData AncEngine::diagnostics() const noexcept {
    DiagnosticsData d{};
    d.running = mIsRunning.load();
    d.sampleRate = mActualSampleRate;
    if (mOutputStream) {
        d.performanceMode = static_cast<int32_t>(mOutputStream->getPerformanceMode());
        d.sharingMode = static_cast<int32_t>(mOutputStream->getSharingMode());
        d.framesPerBurst = mOutputStream->getFramesPerBurst();
        d.outputDeviceId = mOutputStream->getDeviceId();
        const auto xr = mOutputStream->getXRunCount();
        if (xr) d.xRunCount = xr.value();
    }
    if (mInputStream) d.inputDeviceId = mInputStream->getDeviceId();
    d.inputRms = mInputRms.load();
    d.outputRms = mOutputRms.load();
    d.calibrationDelayMs = mActualSampleRate > 0 ? mCalibratedDelaySamples.load() * 1000.0f / mActualSampleRate : 0.0f;
    d.calibrationQuality = mCalibrationQuality.load();
    return d;
}

oboe::DataCallbackResult AncEngine::onBothStreamsReady(
        const void *inputData, int numInputFrames, void *outputData, int numOutputFrames) {
    const auto *in = static_cast<const float *>(inputData);
    auto *out = static_cast<float *>(outputData);
    const int numFrames = std::min(numInputFrames, numOutputFrames);

    float inEnergy = 0.0f;
    float outEnergy = 0.0f;

    if (mCalibrating.load(std::memory_order_relaxed)) {
        int frame = mCalibrationFrame.load(std::memory_order_relaxed);
        for (int i = 0; i < numFrames; ++i, ++frame) {
            const float input = in ? in[i] : 0.0f;
            if (frame < kCalibrationCaptureFrames) mCalibrationInput[frame] = input;
            float y = 0.0f;
            const int probeIndex = frame - kCalibrationProbeStart;
            if (probeIndex >= 0 && probeIndex < kCalibrationProbeFrames) y = mCalibrationProbe[probeIndex];
            out[i] = y;
            inEnergy += input * input;
            outEnergy += y * y;
        }
        mCalibrationFrame.store(frame, std::memory_order_relaxed);
        mCapturedCalibrationFrames.store(std::min(frame, kCalibrationCaptureFrames), std::memory_order_relaxed);
    } else {
        const float gain = mGainCoefficient.load();
        const float phaseOffset = mPhaseOffsetRad.load();
        const float freqHz = mTargetFrequencyHz.load();
        mReferenceOscillator.setFrequency(freqHz, static_cast<float>(mActualSampleRate));

        for (int i = 0; i < numFrames; ++i) {
            const float error = in ? in[i] : 0.0f;
            float cosRef, sinRef;
            mReferenceOscillator.nextSample(cosRef, sinRef, phaseOffset);
            cosRef *= gain;
            sinRef *= gain;
            const std::array<float, kReferenceTaps> referenceVector{cosRef, sinRef};
            float y = std::clamp(mAdaptiveFilter.computeOutput(referenceVector), -1.0f, 1.0f);
            out[i] = y;
            const std::array<float, kReferenceTaps> filteredReferenceVector{
                    mSecondaryPathCos.process(cosRef), mSecondaryPathSin.process(sinRef)};
            mAdaptiveFilter.adapt(filteredReferenceVector, error);
            inEnergy += error * error;
            outEnergy += y * y;
        }
    }

    for (int i = numFrames; i < numOutputFrames; ++i) out[i] = 0.0f;
    if (numFrames > 0) {
        mInputRms.store(std::sqrt(inEnergy / numFrames));
        mOutputRms.store(std::sqrt(outEnergy / numFrames));
    }
    return mIsRunning.load() ? oboe::DataCallbackResult::Continue : oboe::DataCallbackResult::Stop;
}

void AncEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    LOGE("Stream closed after error: %s. direction=%d", oboe::convertToText(error), static_cast<int>(stream->getDirection()));
    mIsRunning.store(false);
}

} // namespace anc::audio
