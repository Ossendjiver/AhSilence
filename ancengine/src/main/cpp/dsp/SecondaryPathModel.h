#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <algorithm>

namespace anc::dsp {

template <std::size_t kTaps>
class SecondaryPathModel {
public:
    SecondaryPathModel() {
        mHistory.fill(0.0f);
        for (auto &buf : mCoefficientBuffers) buf.fill(0.0f);
        mCoefficientBuffers[0][kDefaultDelaySamples] = 1.0f;
    }

    inline float process(float input) noexcept {
        mHistory[mHead] = input;
        const auto &coeffs = mCoefficientBuffers[mActiveBuffer.load(std::memory_order_acquire)];
        float output = 0.0f;
        std::size_t idx = mHead;
        for (std::size_t i = 0; i < kTaps; ++i) {
            output += coeffs[i] * mHistory[idx];
            idx = (idx == 0) ? (kTaps - 1) : (idx - 1);
        }
        mHead = (mHead + 1 == kTaps) ? 0 : (mHead + 1);
        return output;
    }

    void updateCoefficients(const std::array<float, kTaps> &taps) noexcept {
        const int inactive = 1 - mActiveBuffer.load(std::memory_order_relaxed);
        mCoefficientBuffers[inactive] = taps;
        mActiveBuffer.store(inactive, std::memory_order_release);
    }

    void setPureDelay(std::size_t delaySamples, float gain) noexcept {
        std::array<float, kTaps> taps{};
        taps.fill(0.0f);
        taps[std::min(delaySamples, kTaps - 1)] = gain;
        updateCoefficients(taps);
    }

private:
    static constexpr std::size_t kDefaultDelaySamples = 200;
    std::array<std::array<float, kTaps>, 2> mCoefficientBuffers{};
    std::atomic<int> mActiveBuffer{0};
    std::array<float, kTaps> mHistory{};
    std::size_t mHead = 0;
};

} // namespace anc::dsp
