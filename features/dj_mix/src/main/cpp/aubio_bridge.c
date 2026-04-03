/**
 * aubio_bridge.c
 *
 * JNI bridge: BpmAnalyzer.kt  ←→  aubio beat tracker (native C).
 *
 * Exposed JNI method:
 *   com.engfred.musicplayer.feature_dj_mix.data.bpm.BpmAnalyzer.analyzeBeatsNative
 *
 * Input:
 *   float[]  monoSamples — normalised mono PCM in range [-1.0, +1.0]
 *                          already intro-skipped by the Kotlin side
 *   int      sampleRate  — original sample rate (e.g. 44100, 48000)
 *
 * Output:
 *   float[3] { bpm, firstBeatMs, confidence }
 *
 *   firstBeatMs is the BEAT-0 position relative to the start of the
 *   passed samples (i.e. relative to the analysis segment, NOT the
 *   full track).  The Kotlin side adds the intro-skip offset back.
 *
 *   null — on any failure (too short, no beats, bad BPM range, OOM)
 *
 * Algorithm — BPM:
 *   aubio_tempo is a dynamic-programming beat tracker (NOT onset-interval
 *   heuristics). It fits a probabilistic tempo model to the onset detection
 *   function, giving accurate BPM even on swing/groove, dense EDM, and
 *   tracks with irregular transients — all the cases that break TarsosDSP.
 *
 *   buf_size = 1024 samples (~23 ms at 44100 Hz)
 *   hop_size =  512 samples (~12 ms at 44100 Hz)
 *   method   = "default" (specdiff — robust across genres)
 *
 * Algorithm — first beat (Beat-0 extrapolation):
 *   aubio's DP estimator needs WARMUP_BEATS hops to converge. We let it
 *   converge, then use the anchor beat (beats[WARMUP_BEATS]) and the known
 *   tempo to EXTRAPOLATE BACKWARD to beat 0.
 *
 *   firstBeatMs = anchor_ms − WARMUP_BEATS × beat_interval_ms
 *
 *   If the result is negative (beat 0 predates the analysis window), we
 *   phase-wrap into [0, beatInterval) so the returned value is always a
 *   non-negative offset inside the samples we were given.
 *
 *   This eliminates the systematic ~(WARMUP_BEATS × beat_interval) over-
 *   shoot that the old code returned by treating beats[WARMUP_BEATS] as
 *   the first beat directly.
 *
 * Memory safety:
 *   All aubio objects are freed before every return path via a single
 *   cleanup label. No leaks on success, early return, or realloc failure.
 */

#include <jni.h>
#include <math.h>       /* ceilf — explicit; do not rely on aubio.h to pull this in */
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define AUBIO_UNSTABLE 1   /* must precede aubio.h to expose unstable APIs */
#include "aubio.h"

#define LOG_TAG "AubioBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

/* aubio internal buffer sizes — must be power of 2, hop = buf / 2 */
#define BUF_SIZE  1024
#define HOP_SIZE  512

/*
 * Number of beat detections to collect before declaring the tempo model
 * "converged". We then extrapolate BACKWARD by this many intervals to find
 * beat 0, rather than returning beats[WARMUP_BEATS] directly.
 *
 * Keep this at 4: enough convergence, not so many that early onset silence
 * pushes the anchor far from the actual musical content.
 */
#define WARMUP_BEATS  4

/* Hard limits on accepted BPM — covers everything from slow hip-hop to hardstyle */
#define MIN_BPM  55.0f
#define MAX_BPM  215.0f

/* Reject results with fewer detected beats than this */
#define MIN_BEATS  8

/* Starting capacity of the beat timestamp buffer; doubles on overflow */
#define BEAT_BUF_INIT  512

/* ── JNI function ──────────────────────────────────────────────────────────── */

/*
 * JNI name mangling rule: underscores in package segments → _1
 *   feature_dj_mix  →  feature_1dj_1mix
 */
JNIEXPORT jfloatArray JNICALL
Java_com_engfred_musicplayer_feature_1dj_1mix_data_bpm_BpmAnalyzer_analyzeBeatsNative(
        JNIEnv  *env,
        jobject  thiz,
        jfloatArray monoSamples,
        jint        sampleRate)
{
    /* ── Input validation ──────────────────────────────────────────────────── */
    if (monoSamples == NULL) {
        LOGE("monoSamples array is null");
        return NULL;
    }

    jsize numSamples = (*env)->GetArrayLength(env, monoSamples);
    if (numSamples < (jsize)(BUF_SIZE * 8)) {
        LOGE("Too few samples (%d) — minimum required: %d", (int)numSamples, BUF_SIZE * 8);
        return NULL;
    }

    jfloat *samples = (*env)->GetFloatArrayElements(env, monoSamples, NULL);
    if (samples == NULL) {
        LOGE("GetFloatArrayElements returned NULL — out of memory?");
        return NULL;
    }

    /* ── aubio objects (all freed in cleanup) ──────────────────────────────── */
    aubio_tempo_t *tempo  = NULL;
    fvec_t        *input  = NULL;
    fvec_t        *output = NULL;

    /* Beat timestamp accumulator */
    float *beats   = NULL;
    int    beatCap = BEAT_BUF_INIT;
    int    beatCnt = 0;

    jfloatArray result = NULL;

    /* ── Allocate aubio objects ─────────────────────────────────────────────── */
    tempo = new_aubio_tempo("default",
            (uint_t)BUF_SIZE,
            (uint_t)HOP_SIZE,
            (uint_t)sampleRate);
    if (!tempo) {
        LOGE("new_aubio_tempo failed (sampleRate=%d bufSize=%d hopSize=%d)",
                (int)sampleRate, BUF_SIZE, HOP_SIZE);
        goto cleanup;
    }

    input  = new_fvec((uint_t)HOP_SIZE);
    output = new_fvec(1);
    if (!input || !output) {
        LOGE("new_fvec allocation failed — out of memory");
        goto cleanup;
    }

    beats = (float *)malloc((size_t)beatCap * sizeof(float));
    if (!beats) {
        LOGE("malloc for beat buffer failed — out of memory");
        goto cleanup;
    }

    /* ── Feed samples in hops ──────────────────────────────────────────────── */
    {
        jsize    pos = 0;
        uint_t   i;

        while (pos + (jsize)HOP_SIZE <= numSamples) {
            /* Copy one hop of samples into the aubio input vector */
            for (i = 0; i < (uint_t)HOP_SIZE; i++) {
                input->data[i] = (smpl_t)samples[pos + (jsize)i];
            }

            aubio_tempo_do(tempo, input, output);

            /*
             * output->data[0] != 0 signals a beat was detected in this hop.
             * aubio_tempo_get_last_s() returns the beat's position in the audio
             * stream (seconds since analysis start, not since the hop start).
             */
            if (output->data[0] != 0) {
                smpl_t beat_s = aubio_tempo_get_last_s(tempo);

                if (beatCnt >= beatCap) {
                    int    newCap = beatCap * 2;
                    float *newBuf = (float *)realloc(beats,
                            (size_t)newCap * sizeof(float));
                    if (!newBuf) {
                        LOGE("realloc for beat buffer failed — out of memory");
                        goto cleanup;
                    }
                    beats   = newBuf;
                    beatCap = newCap;
                }

                beats[beatCnt++] = (float)beat_s;
            }

            pos += (jsize)HOP_SIZE;
        }
    }

    /* ── Extract and validate results ──────────────────────────────────────── */
    {
        smpl_t bpm        = aubio_tempo_get_bpm(tempo);
        smpl_t confidence = aubio_tempo_get_confidence(tempo);

        LOGD("aubio result: bpm=%.2f confidence=%.3f beats_detected=%d",
                (double)bpm, (double)confidence, beatCnt);

        if (beatCnt < MIN_BEATS) {
            LOGW("Too few beats (%d < %d) — analysis window may be too short",
                    beatCnt, MIN_BEATS);
            goto cleanup;
        }

        if ((float)bpm < MIN_BPM || (float)bpm > MAX_BPM) {
            LOGW("BPM %.2f out of accepted range [%.0f, %.0f]",
                    (double)bpm, (double)MIN_BPM, (double)MAX_BPM);
            goto cleanup;
        }

        /*
         * ── Beat-0 Extrapolation ────────────────────────────────────────────
         *
         * The old approach returned beats[WARMUP_BEATS] as "firstBeatMs".
         * That is actually the (WARMUP_BEATS+1)-th detected beat, causing a
         * systematic overshoot of ~(WARMUP_BEATS × beat_interval_ms).
         *
         * New approach:
         *   1. Take the anchor beat: beats[firstBeatIdx].
         *   2. Compute beat_interval_ms = 60 000 / bpm.
         *   3. Extrapolate backward: beat0_ms = anchor_ms − firstBeatIdx × interval.
         *   4. If beat0_ms < 0 (beat 0 predates the analysis window), phase-wrap
         *      into [0, interval) — the nearest valid beat-0 equivalent that lies
         *      within the samples we were given.
         *
         * Phase-wrapping is safe because the beat grid is periodic: beat 0 at
         * t=X is musically identical to beat 0 at t=X+N×interval for any integer N.
         * The Kotlin beat-snap pass will refine the position further.
         */
        int   firstBeatIdx   = (beatCnt > WARMUP_BEATS) ? WARMUP_BEATS : 0;
        float beatIntervalMs = 60000.0f / (float)bpm;
        float anchorMs       = beats[firstBeatIdx] * 1000.0f;
        float firstBeatMs    = anchorMs - (float)firstBeatIdx * beatIntervalMs;

        if (firstBeatMs < 0.0f) {
            /*
             * How many full intervals must we add to land at or just after 0?
             * ceilf(-firstBeatMs / beatIntervalMs) gives the smallest positive
             * integer N such that firstBeatMs + N*interval >= 0.
             */
            int cycles   = (int)ceilf(-firstBeatMs / beatIntervalMs);
            firstBeatMs += (float)cycles * beatIntervalMs;
        }

        LOGD("Beat-0 extrapolation: anchor=beats[%d]=%.1fms interval=%.1fms → beat0=%.1fms",
                firstBeatIdx, (double)anchorMs,
                (double)beatIntervalMs, (double)firstBeatMs);

        /* Build return array: float[3] = { bpm, firstBeatMs, confidence } */
        result = (*env)->NewFloatArray(env, 3);
        if (result != NULL) {
            jfloat outData[3];
            outData[0] = (jfloat)bpm;
            outData[1] = (jfloat)firstBeatMs;
            outData[2] = (jfloat)confidence;
            (*env)->SetFloatArrayRegion(env, result, 0, 3, outData);
        } else {
            LOGE("NewFloatArray(3) failed — out of memory");
        }
    }

    /* ── Cleanup (always executed) ─────────────────────────────────────────── */
    cleanup:
    if (input)  del_fvec(input);
    if (output) del_fvec(output);
    if (tempo)  del_aubio_tempo(tempo);
    if (beats)  free(beats);

    /* JNI_ABORT: we never modified the Java array, so do not copy back */
    (*env)->ReleaseFloatArrayElements(env, monoSamples, samples, JNI_ABORT);

    return result;
}