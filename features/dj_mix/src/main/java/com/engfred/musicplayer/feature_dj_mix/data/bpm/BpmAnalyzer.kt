package com.engfred.musicplayer.feature_dj_mix.data.bpm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.UniversalAudioInputStream
import be.tarsos.dsp.onsets.ComplexOnsetDetector
import be.tarsos.dsp.onsets.OnsetHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps TarsosDSP's onset-based BPM detection behind a single suspend function.
 *
 * ── Why NOT AudioDispatcherFactory ───────────────────────────────────────────
 * [be.tarsos.dsp:core:2.5] does NOT expose AudioDispatcherFactory.fromFloatArray
 * on Android — that factory method internally uses javax.sound.sampled.AudioFormat
 * which does not exist on Android and will throw at runtime. The correct Android
 * pattern (confirmed via TarsosDSP GitHub issues #33, #60, #72) is to construct
 * AudioDispatcher directly using UniversalAudioInputStream, which is part of the
 * core module and has zero javax.sound dependency.
 *
 * ── Pipeline ─────────────────────────────────────────────────────────────────
 *   URI  ->  MediaExtractor + MediaCodec  (decode to 16-bit PCM)
 *        ->  mix down to mono             (if stereo / multi-channel)
 *        ->  UniversalAudioInputStream    (TarsosDSP I/O bridge, core module)
 *        ->  AudioDispatcher              (frames PCM into fixed-size buffers)
 *        ->  ComplexOnsetDetector         (detects onset timestamps)
 *        ->  median inter-onset interval  ->  BPM
 *
 * Only the first MAX_ANALYSIS_DURATION_MS of each file is decoded so the
 * background worker completes quickly even on very long tracks.
 */
@Singleton
class BpmAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "BpmAnalyzer"

        /** Decode at most this many ms of audio — sufficient for reliable BPM. */
        private const val MAX_ANALYSIS_DURATION_MS = 60_000L

        /**
         * FFT window size for onset detection. Must be a power of 2.
         * 1024 = ~23 ms at 44100 Hz — good tradeoff between time/frequency resolution.
         */
        private const val BUFFER_SIZE = 1024

        /** Sensitivity of the onset detector (lower = more onsets detected). */
        private const val ONSET_THRESHOLD = 0.3

        /** Inter-onset pairs closer than this are discarded as noise bursts. */
        private const val MIN_INTER_ONSET_SEC = 0.25f

        /** Clamp estimated BPM to a sensible DJ range. */
        private const val MIN_BPM = 60f
        private const val MAX_BPM = 200f
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Analyses [uri] and returns a BPM estimate, or null if analysis fails
     * (unreadable file, too short, codec error, etc.).
     *
     * Always executes on Dispatchers.IO — safe to call from any coroutine context.
     */
    suspend fun analyzeBpm(uri: Uri): Float? = withContext(Dispatchers.IO) {
        try {
            val (pcmBytes, sampleRate, channelCount) = decodeToPcm(uri)
                ?: return@withContext null

            val monoBytes = if (channelCount > 1) mixToMono(pcmBytes, channelCount) else pcmBytes

            runOnsetBpmDetection(monoBytes, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "BPM analysis failed for $uri", e)
            null
        }
    }

    // ── PCM decoding ──────────────────────────────────────────────────────────

    /**
     * Decodes audio from [uri] to raw 16-bit signed little-endian PCM bytes.
     * Returns Triple(pcmBytes, sampleRate, channelCount), or null on error.
     * Decoding stops after MAX_ANALYSIS_DURATION_MS to keep analysis fast.
     */
    private fun decodeToPcm(uri: Uri): Triple<ByteArray, Int, Int>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)

            // Find the first audio track
            var audioTrackIndex = -1
            var mediaFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    mediaFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || mediaFormat == null) {
                Log.w(TAG, "No audio track found in $uri")
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            val mime        = mediaFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate  = mediaFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE, 44100)
            val channelCount = mediaFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT, 1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(mediaFormat, null, null, 0)
            codec.start()

            val output     = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos   = false
            var outputEos  = false

            while (!outputEos) {
                // Feed input
                if (!inputEos) {
                    val inputIdx = codec.dequeueInputBuffer(10_000L)
                    if (inputIdx >= 0) {
                        val inBuf      = codec.getInputBuffer(inputIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        when {
                            sampleSize < 0 -> {
                                // Natural EOF
                                codec.queueInputBuffer(inputIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            }
                            extractor.sampleTime / 1_000 >= MAX_ANALYSIS_DURATION_MS -> {
                                // Enough audio — signal EOS early
                                codec.queueInputBuffer(inputIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            }
                            else -> {
                                codec.queueInputBuffer(inputIdx, 0, sampleSize,
                                    extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain output
                when (val outputIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER    -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outputIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)
                            output.write(chunk)
                        }
                        codec.releaseOutputBuffer(outputIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                    }
                }
            }

            Triple(output.toByteArray(), sampleRate, channelCount)
        } catch (e: Exception) {
            Log.e(TAG, "PCM decode failed for $uri", e)
            null
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    // ── Audio utilities ───────────────────────────────────────────────────────

    /**
     * Mixes interleaved multi-channel 16-bit little-endian PCM down to mono.
     * Input layout per frame: [ch0_lo, ch0_hi, ch1_lo, ch1_hi, ...]
     */
    private fun mixToMono(pcm: ByteArray, channelCount: Int): ByteArray {
        val bytesPerFrame = channelCount * 2
        val frameCount    = pcm.size / bytesPerFrame
        val mono          = ByteArray(frameCount * 2)
        val src           = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val dst           = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN)

        repeat(frameCount) {
            var sum = 0L
            repeat(channelCount) { sum += src.short.toLong() }
            dst.putShort((sum / channelCount).toShort())
        }
        return mono
    }

    // ── TarsosDSP onset -> BPM ────────────────────────────────────────────────

    /**
     * Feeds raw mono 16-bit PCM bytes into TarsosDSP via UniversalAudioInputStream
     * + AudioDispatcher, runs ComplexOnsetDetector, then derives BPM from the
     * collected onset timestamps.
     *
     * UniversalAudioInputStream is from be.tarsos.dsp.io — part of the core module
     * with no javax.sound dependency — and accepts any InputStream plus a
     * TarsosDSPAudioFormat descriptor.
     *
     * MediaCodec always outputs little-endian PCM on Android, so bigEndian = false.
     */
    private fun runOnsetBpmDetection(monoPcmBytes: ByteArray, sampleRate: Int): Float? {
        val minBytes = BUFFER_SIZE * 2 * 4  // at least 4 full buffers
        if (monoPcmBytes.size < minBytes) {
            Log.w(TAG, "Audio too short for BPM detection (${monoPcmBytes.size} bytes)")
            return null
        }

        val format = TarsosDSPAudioFormat(
            sampleRate.toFloat(), // sample rate
            16,                   // sample size in bits
            1,                    // channels (mono)
            true,                 // signed
            false                 // bigEndian — Android MediaCodec outputs little-endian
        )

        val inputStream = UniversalAudioInputStream(
            ByteArrayInputStream(monoPcmBytes),
            format
        )

        val dispatcher = AudioDispatcher(inputStream, BUFFER_SIZE, 0)

        val onsetTimestamps = mutableListOf<Float>()
        val onsetDetector   = ComplexOnsetDetector(BUFFER_SIZE, ONSET_THRESHOLD)
        onsetDetector.setHandler(OnsetHandler { timeSeconds, _ ->
            onsetTimestamps.add(timeSeconds.toFloat())
        })
        dispatcher.addAudioProcessor(onsetDetector)

        // Runs synchronously on Dispatchers.IO (called from analyzeBpm)
        dispatcher.run()

        return estimateBpmFromOnsets(onsetTimestamps)
    }

    /**
     * Derives BPM from onset timestamps (seconds) using the median inter-onset
     * interval. Median is more robust than mean against missed/doubled beats.
     */
    private fun estimateBpmFromOnsets(timestamps: List<Float>): Float? {
        if (timestamps.size < 4) {
            Log.w(TAG, "Too few onsets (${timestamps.size}) for reliable BPM")
            return null
        }

        val intervals = timestamps
            .zipWithNext { a, b -> b - a }
            .filter { it >= MIN_INTER_ONSET_SEC }

        if (intervals.isEmpty()) return null

        val median = intervals.sorted()[intervals.size / 2]
        return (60f / median).coerceIn(MIN_BPM, MAX_BPM)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun MediaFormat.getIntegerSafe(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}