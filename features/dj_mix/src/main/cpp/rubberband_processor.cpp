/**
 * rubberband_processor.cpp — PRO-GRADE JNI bridge for RubberBand.
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

    // Optimized for ARM Mobile CPUs to prevent AudioTrack starvation
    auto opts = RubberBandStretcher::OptionProcessRealTime |
            RubberBandStretcher::OptionTransientsCrisp |
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

// ZERO-COPY DIRECT BUFFER PROCESSING WITH EXACT OFFSET
JNIEXPORT void JNICALL
Java_com_engfred_musicplayer_feature_1dj_1mix_data_crossfade_RubberBandAudioProcessor_nativeProcess(
        JNIEnv* env, jobject, jlong handle, jobject directInputBuffer,
        jint byteOffset, jint frameCount, jboolean isFinal) {

    auto* h = toHandle(handle);
    if (!h || frameCount < 0) return;

    int ch = h->channels;

    if (frameCount > 0 && directInputBuffer != nullptr) {
        // Grab the absolute start of the memory block
        void* baseMemory = env->GetDirectBufferAddress(directInputBuffer);
        if (!baseMemory) return;

        // Shift the pointer forward by the exact byte offset ExoPlayer requires
        auto* src = reinterpret_cast<int16_t*>(static_cast<uint8_t*>(baseMemory) + byteOffset);

        for (int c = 0; c < ch; ++c) {
            if (static_cast<int>(h->inBufs[c].size()) < frameCount) {
                h->inBufs[c].resize(frameCount);
                h->inPtrs[c] = h->inBufs[c].data();
            }
        }

        // Fast SIMD conversion directly from correct memory offset
        for (int i = 0; i < frameCount; ++i) {
            for (int c = 0; c < ch; ++c) {
                h->inBufs[c][i] = src[i * ch + c] / 32768.0f;
            }
        }
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

// ZERO-COPY DIRECT BUFFER RETRIEVAL
JNIEXPORT jint JNICALL
Java_com_engfred_musicplayer_feature_1dj_1mix_data_crossfade_RubberBandAudioProcessor_nativeRetrieve(
        JNIEnv* env, jobject, jlong handle, jobject directOutputBuffer, jint maxFrames) {

    auto* h = toHandle(handle);
    if (!h || maxFrames <= 0 || directOutputBuffer == nullptr) return 0;

    // Grab direct memory pointer from ExoPlayer's buffer (always written from offset 0)
    int16_t* dst = static_cast<int16_t*>(env->GetDirectBufferAddress(directOutputBuffer));
    if (!dst) return 0;

    int ch = h->channels;
    for (int c = 0; c < ch; ++c) {
        if (static_cast<int>(h->outBufs[c].size()) < maxFrames) {
            h->outBufs[c].resize(maxFrames);
            h->outPtrs[c] = h->outBufs[c].data();
        }
    }

    size_t got = h->stretcher->retrieve(
            h->outPtrs.data(), static_cast<size_t>(maxFrames));

    if (got == 0) return 0;

    // Fast SIMD conversion back to int16 PCM directly into Java memory
    for (size_t i = 0; i < got; ++i) {
        for (int c = 0; c < ch; ++c) {
            float val = h->outPtrs[c][i];
            if (val > 1.0f) val = 1.0f;
            else if (val < -1.0f) val = -1.0f;

            dst[i * ch + c] = static_cast<int16_t>(val * 32767.0f);
        }
    }

    return static_cast<jint>(got);
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