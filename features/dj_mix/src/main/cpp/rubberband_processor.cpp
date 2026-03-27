/**
 * rubberband_processor.cpp — JNI bridge for RubberBand time-stretching.
 */

#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <atomic>
#include <vector>
#include "rubberband/RubberBandStretcher.h"

using namespace RubberBand;

#define LOG_TAG "RubberBandBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

struct RBHandle {
    RubberBandStretcher* stretcher;
    int                  channels;
    std::vector<std::vector<float>> inBufs;
    std::vector<std::vector<float>> outBufs;
    std::vector<float*>             inPtrs;
    std::vector<float*>             outPtrs;
};

static RBHandle* toHandle(jlong h) { return reinterpret_cast<RBHandle*>(static_cast<intptr_t>(h)); }

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_engfred_musicplayer_feature_1dj_1mix_data_crossfade_RubberBandAudioProcessor_nativeCreate(
        JNIEnv*, jobject, jint sampleRate, jint channels) {

    auto opts = RubberBandStretcher::OptionProcessRealTime |
            RubberBandStretcher::OptionPitchHighConsistency |
            RubberBandStretcher::OptionWindowShort;

    auto* h = new RBHandle();
    h->channels  = channels;
    h->stretcher = new RubberBandStretcher(
            static_cast<size_t>(sampleRate),
            static_cast<size_t>(channels),
            opts, 1.0, 1.0);

    h->inBufs.assign(channels, std::vector<float>(1024));
    h->outBufs.assign(channels, std::vector<float>(1024));
    h->inPtrs.resize(channels);
    h->outPtrs.resize(channels);
    for (int c = 0; c < channels; ++c) {
        h->inPtrs[c]  = h->inBufs[c].data();
        h->outPtrs[c] = h->outBufs[c].data();
    }

    LOGD("Created: sr=%d ch=%d", sampleRate, channels);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(h));
}

/**
 * ROOT CAUSE FIX 2: Pre-warm the stretcher internal buffers.
 * Pushes 4096 frames of silence through the stretcher to fill FFT windows
 * and minimize initial output latency.
 */
JNIEXPORT void JNICALL
Java_com_engfred_musicplayer_feature_1dj_1mix_data_crossfade_RubberBandAudioProcessor_nativePrewarm(
        JNIEnv* env, jobject, jlong handle) {
    auto* h = toHandle(handle);
    if (!h) return;

    size_t prewarmFrames = 4096;
    std::vector<float> silence(prewarmFrames, 0.0f);
    std::vector<float*> silPtrs(h->channels, silence.data());

    h->stretcher->process(silPtrs.data(), prewarmFrames, false);
    LOGD("Native RubberBand Pre-warmed");
}

JNIEXPORT void JNICALL
Java_com_engfred_musicplayer_feature_1dj_1mix_data_crossfade_RubberBandAudioProcessor_nativeSetTimeRatio(
        JNIEnv*, jobject, jlong handle, jdouble ratio) {
    auto* h = toHandle(handle);
    if (h) h->stretcher->setTimeRatio(ratio);
}

JNIEXPORT void JNICALL
Java_com_engfred_musicplayer_feature_1dj_1mix_data_crossfade_RubberBandAudioProcessor_nativeProcess(
        JNIEnv* env, jobject, jlong handle, jfloatArray interleavedInput,
        jint frameCount, jboolean isFinal) {

    auto* h = toHandle(handle);
    if (!h || frameCount < 0) return;

    int ch = h->channels;

    if (frameCount > 0) {
        for (int c = 0; c < ch; ++c) {
            if (static_cast<int>(h->inBufs[c].size()) < frameCount) {
                h->inBufs[c].resize(frameCount);
                h->inPtrs[c] = h->inBufs[c].data();
            }
        }

        jfloat* src = env->GetFloatArrayElements(interleavedInput, nullptr);
        if (!src) return;
        for (int i = 0; i < frameCount; ++i)
            for (int c = 0; c < ch; ++c)
                h->inBufs[c][i] = src[i * ch + c];
        env->ReleaseFloatArrayElements(interleavedInput, src, JNI_ABORT);
    }

    h->stretcher->process(
            h->inPtrs.data(),
            static_cast<size_t>(frameCount),
            isFinal == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_engfred_musicplayer_feature_1dj_1mix_data_crossfade_RubberBandAudioProcessor_nativeAvailable(
        JNIEnv*, jobject, jlong handle) {
    auto* h = toHandle(handle);
    return h ? static_cast<jint>(h->stretcher->available()) : 0;
}

JNIEXPORT jfloatArray JNICALL
Java_com_engfred_musicplayer_feature_1dj_1mix_data_crossfade_RubberBandAudioProcessor_nativeRetrieve(
        JNIEnv* env, jobject, jlong handle, jint frameCount) {

    auto* h = toHandle(handle);
    if (!h || frameCount <= 0) return nullptr;

    int ch = h->channels;
    for (int c = 0; c < ch; ++c) {
        if (static_cast<int>(h->outBufs[c].size()) < frameCount) {
            h->outBufs[c].resize(frameCount);
            h->outPtrs[c] = h->outBufs[c].data();
        }
    }

    size_t got = h->stretcher->retrieve(
            h->outPtrs.data(), static_cast<size_t>(frameCount));
    if (got == 0) return nullptr;

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(got * ch));
    if (!result) return nullptr;

    jfloat* dst = env->GetFloatArrayElements(result, nullptr);
    for (size_t i = 0; i < got; ++i)
        for (int c = 0; c < ch; ++c)
            dst[i * ch + c] = h->outPtrs[c][i];
    env->ReleaseFloatArrayElements(result, dst, 0);
    return result;
}

JNIEXPORT void JNICALL
Java_com_engfred_musicplayer_feature_1dj_1mix_data_crossfade_RubberBandAudioProcessor_nativeReset(
        JNIEnv*, jobject, jlong handle) {
    auto* h = toHandle(handle);
    if (h) { h->stretcher->reset(); LOGD("Reset"); }
}

JNIEXPORT void JNICALL
Java_com_engfred_musicplayer_feature_1dj_1mix_data_crossfade_RubberBandAudioProcessor_nativeDelete(
        JNIEnv*, jobject, jlong handle) {
    auto* h = toHandle(handle);
    if (!h) return;
    delete h->stretcher;
    delete h;
    LOGD("Deleted");
}

} // extern "C"