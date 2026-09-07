#include <jni.h>

#include "../audio/AncEngine.h"

using anc::audio::AncEngine;

namespace {
inline AncEngine *toEngine(jlong handle) { return reinterpret_cast<AncEngine *>(handle); }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bted_ahsilence_data_engine_NativeAudioDSP_nativeCreate(JNIEnv *, jobject, jint sampleRate) {
    auto *engine = new AncEngine(static_cast<int32_t>(sampleRate));
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_bted_ahsilence_data_engine_NativeAudioDSP_nativeStart(JNIEnv *, jobject, jlong handle) {
    AncEngine *engine = toEngine(handle);
    return (engine != nullptr && engine->start() == oboe::Result::OK) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_bted_ahsilence_data_engine_NativeAudioDSP_nativeStop(JNIEnv *, jobject, jlong handle) {
    if (AncEngine *engine = toEngine(handle)) engine->stop();
}

JNIEXPORT void JNICALL
Java_com_bted_ahsilence_data_engine_NativeAudioDSP_nativeSetFrequency(JNIEnv *, jobject, jlong handle, jfloat frequencyHz) {
    if (AncEngine *engine = toEngine(handle)) engine->setTargetFrequency(static_cast<float>(frequencyHz));
}

JNIEXPORT void JNICALL
Java_com_bted_ahsilence_data_engine_NativeAudioDSP_nativeUpdateParameters(JNIEnv *, jobject, jlong handle, jfloat gainPercent, jfloat phaseDegrees) {
    if (AncEngine *engine = toEngine(handle)) engine->updateParameters(static_cast<float>(gainPercent), static_cast<float>(phaseDegrees));
}

JNIEXPORT void JNICALL
Java_com_bted_ahsilence_data_engine_NativeAudioDSP_nativeBeginCalibration(JNIEnv *, jobject, jlong handle) {
    if (AncEngine *engine = toEngine(handle)) engine->beginCalibration();
}

JNIEXPORT jfloatArray JNICALL
Java_com_bted_ahsilence_data_engine_NativeAudioDSP_nativeFinishCalibration(JNIEnv *env, jobject, jlong handle) {
    jfloat values[7]{};
    if (AncEngine *engine = toEngine(handle)) {
        const auto c = engine->finishCalibration();
        values[0] = c.success ? 1.0f : 0.0f;
        values[1] = static_cast<float>(c.delaySamples);
        values[2] = c.pathGain;
        values[3] = c.quality;
        values[4] = static_cast<float>(c.sampleRate);
        values[5] = static_cast<float>(c.outputDeviceId);
        values[6] = static_cast<float>(c.inputDeviceId);
    }
    jfloatArray result = env->NewFloatArray(7);
    env->SetFloatArrayRegion(result, 0, 7, values);
    return result;
}

JNIEXPORT void JNICALL
Java_com_bted_ahsilence_data_engine_NativeAudioDSP_nativeApplyCalibration(JNIEnv *, jobject, jlong handle, jint delaySamples, jfloat gain, jfloat quality) {
    if (AncEngine *engine = toEngine(handle)) engine->applyCalibration(delaySamples, gain, quality);
}

JNIEXPORT jfloatArray JNICALL
Java_com_bted_ahsilence_data_engine_NativeAudioDSP_nativeDiagnostics(JNIEnv *env, jobject, jlong handle) {
    jfloat values[12]{};
    if (AncEngine *engine = toEngine(handle)) {
        const auto d = engine->diagnostics();
        values[0] = d.running ? 1.0f : 0.0f;
        values[1] = static_cast<float>(d.sampleRate);
        values[2] = static_cast<float>(d.performanceMode);
        values[3] = static_cast<float>(d.sharingMode);
        values[4] = static_cast<float>(d.framesPerBurst);
        values[5] = static_cast<float>(d.xRunCount);
        values[6] = static_cast<float>(d.outputDeviceId);
        values[7] = static_cast<float>(d.inputDeviceId);
        values[8] = d.inputRms;
        values[9] = d.outputRms;
        values[10] = d.calibrationDelayMs;
        values[11] = d.calibrationQuality;
    }
    jfloatArray result = env->NewFloatArray(12);
    env->SetFloatArrayRegion(result, 0, 12, values);
    return result;
}

JNIEXPORT void JNICALL
Java_com_bted_ahsilence_data_engine_NativeAudioDSP_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete toEngine(handle);
}

}
