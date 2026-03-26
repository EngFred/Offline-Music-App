package com.engfred.musicplayer.feature_dj_mix.data.bpm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Performs BPM analysis and first-beat detection for DJ Auto-Mix.
 */
@Singleton
class BpmAnalyzer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "BpmAnalyzer"

        /** Decode at most this many ms — enough for reliable BPM even on long tracks. */
        private const val MAX_ANALYSIS_DURATION_MS = 90_000L

        /**
         * Discard this many seconds from the start of decoded audio before
         * passing to the beat tracker. Eliminates ambient/non-percussive intros
         * that confuse the onset detection function.
         * Skipped only if the track is longer than 2× this value.
         */
        private const val ANALYSIS_SKIP_SECONDS = 15f

        /** Clamp the native result to a sane DJ range. */
        private const val MIN_BPM = 55f
        private const val MAX_BPM = 215f
    }

    data class BpmAnalysisResult(
        val bpm: Float,
        val firstBeatMs: Long,
        val amplitude: Float,
        val waveformEnvelope: FloatArray = FloatArray(0)   // ── NEW ──
    )

    init {
        System.loadLibrary("bpm_analyzer")
    }

    private external fun analyzeBeatsNative(monoSamples: FloatArray, sampleRate: Int): FloatArray?

    suspend fun analyzeBpm(uri: Uri): BpmAnalysisResult? = withContext(Dispatchers.IO) {
        try {
            val (pcmBytes, sampleRate, channelCount) = decodeToPcm(uri)
                ?: return@withContext null

            val monoBytes = if (channelCount > 1) mixToMono(pcmBytes, channelCount) else pcmBytes

            // Computed before the intro skip so auto-gain reflects the whole song.
            val amplitude = calculateKWeightedAmplitude(monoBytes, sampleRate)

            // ── NEW: compute real waveform envelope from the full decoded mono PCM ───
            // Done BEFORE the intro skip so the envelope represents the whole track,
            // not just the analysed portion. This gives the visualiser a shape that
            // matches the actual audio content from start to finish.
            val waveformEnvelope = computeWaveformEnvelope(monoBytes, sampleRate)

            // Intro skip
            val skipSamples = (ANALYSIS_SKIP_SECONDS * sampleRate).toInt()
            val skipBytes   = skipSamples * 2
            val (analysisBytes, skippedSeconds) = if (monoBytes.size > skipBytes * 2) {
                monoBytes.copyOfRange(skipBytes, monoBytes.size) to ANALYSIS_SKIP_SECONDS
            } else {
                Log.d(TAG, "Track too short for intro skip — analysing from start")
                monoBytes to 0f
            }

            val numSamples  = analysisBytes.size / 2
            val floatSamples = FloatArray(numSamples)
            val buf = ByteBuffer.wrap(analysisBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                floatSamples[i] = buf.short.toFloat() / Short.MAX_VALUE
            }

            val nativeResult = analyzeBeatsNative(floatSamples, sampleRate)
                ?: run {
                    Log.w(TAG, "analyzeBeatsNative returned null for $uri")
                    return@withContext null
                }

            val bpm                  = nativeResult[0].coerceIn(MIN_BPM, MAX_BPM)
            val firstBeatMsRelative  = nativeResult[1].toLong()
            val confidence           = nativeResult[2]

            // Add the intro-skip offset back so the timestamp maps to the real track position
            val firstBeatMs = firstBeatMsRelative + (skippedSeconds * 1000f).toLong()

            Log.d(TAG, "BPM=$bpm firstBeat=${firstBeatMs}ms " +
                    "confidence=${String.format("%.3f", confidence)} " +
                    "kRms=${String.format("%.4f", amplitude)}")

            BpmAnalysisResult(
                bpm              = bpm,
                firstBeatMs      = firstBeatMs,
                amplitude        = amplitude,
                waveformEnvelope = waveformEnvelope   // ── NEW ──
            )

        } catch (e: Exception) {
            Log.e(TAG, "BPM analysis failed for $uri", e)
            null
        }
    }

    // ── K-weighted amplitude (EBU R128 / ITU-R BS.1770-4) ────────────────────

    private data class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    private fun designHighShelf(fc: Double, gainDb: Double, sampleRate: Double): BiquadCoeffs {
        val A     = Math.pow(10.0, gainDb / 40.0)
        val w0    = 2.0 * Math.PI * fc / sampleRate
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val sqrtA = Math.sqrt(A)
        val alpha = sinW0 * 0.7071067811865476
        val twoSqrtAAlpha = 2.0 * sqrtA * alpha

        val b0 = A  * ((A + 1) + (A - 1) * cosW0 + twoSqrtAAlpha)
        val b1 = -2.0 * A * ((A - 1) + (A + 1) * cosW0)
        val b2 = A  * ((A + 1) + (A - 1) * cosW0 - twoSqrtAAlpha)
        val a0 =       (A + 1) - (A - 1) * cosW0 + twoSqrtAAlpha
        val a1 = 2.0 * ((A - 1) - (A + 1) * cosW0)
        val a2 =       (A + 1) - (A - 1) * cosW0 - twoSqrtAAlpha

        return BiquadCoeffs(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun designHighPass2nd(fc: Double, sampleRate: Double): BiquadCoeffs {
        val w0    = 2.0 * Math.PI * fc / sampleRate
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val alpha = sinW0 * 0.7071067811865476

        val b0 = (1.0 + cosW0) / 2.0
        val b1 = -(1.0 + cosW0)
        val b2 = (1.0 + cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha

        return BiquadCoeffs(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun applyBiquad(input: FloatArray, c: BiquadCoeffs): FloatArray {
        val output = FloatArray(input.size)
        var w1 = 0.0
        var w2 = 0.0
        for (i in input.indices) {
            val x  = input[i].toDouble()
            val y  = c.b0 * x + w1
            w1     = c.b1 * x - c.a1 * y + w2
            w2     = c.b2 * x - c.a2 * y
            output[i] = y.toFloat()
        }
        return output
    }

    private fun calculateKWeightedAmplitude(pcmBytes: ByteArray, sampleRate: Int): Float {
        if (pcmBytes.isEmpty()) return 0f
        val numSamples = pcmBytes.size / 2
        if (numSamples == 0) return 0f

        val buf     = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(numSamples) { buf.short.toFloat() / Short.MAX_VALUE }

        val fs       = sampleRate.toDouble()
        val stage1   = designHighShelf(1681.974, 4.0, fs)
        val stage2   = designHighPass2nd(38.134, fs)
        val filtered = applyBiquad(applyBiquad(samples, stage1), stage2)

        var sumSq = 0.0
        for (s in filtered) sumSq += s.toDouble() * s

        return sqrt(sumSq / numSamples).toFloat()
    }

    // ── PCM decoding ──────────────────────────────────────────────────────────

    private fun decodeToPcm(uri: Uri): Triple<ByteArray, Int, Int>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)

            var audioTrackIndex = -1
            var mediaFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt  = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    mediaFormat     = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || mediaFormat == null) {
                Log.w(TAG, "No audio track found in $uri")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime         = mediaFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate   = mediaFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE, 44100)
            val channelCount = mediaFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT, 1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(mediaFormat, null, null, 0)
            codec.start()

            val output     = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos   = false
            var outputEos  = false

            while (!outputEos) {
                if (!inputEos) {
                    val inputIdx = codec.dequeueInputBuffer(10_000L)
                    if (inputIdx >= 0) {
                        val inBuf      = codec.getInputBuffer(inputIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        when {
                            sampleSize < 0 -> {
                                codec.queueInputBuffer(inputIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            }
                            extractor.sampleTime / 1_000 >= MAX_ANALYSIS_DURATION_MS -> {
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
                when (val outputIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER       -> Unit
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

    private fun MediaFormat.getIntegerSafe(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default


    /**
     * Computes a [numBars]-element normalised RMS amplitude envelope from mono PCM bytes.
     *
     * Each bar is the root-mean-square of [samplesPerBar] consecutive 16-bit samples,
     * then the whole array is normalised so the loudest bar = 1.0. The result becomes
     * the static shape of the CrossfadeEngine waveform visualiser — actual track content
     * instead of a synthetic kick/snare pattern.
     *
     * [monoBytes] is expected to be the full decoded mono PCM (before intro skip) so
     * the envelope covers the whole track, not just the analysed portion.
     */
    private fun computeWaveformEnvelope(
        monoBytes: ByteArray,
        sampleRate: Int,
        numBars: Int = 128
    ): FloatArray {
        if (monoBytes.size < 2) return FloatArray(numBars) { 0.1f }

        val numSamples   = monoBytes.size / 2
        val samplesPerBar = (numSamples.toFloat() / numBars).toInt().coerceAtLeast(1)
        val buf          = ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN)
        val rawEnvelope  = FloatArray(numBars)
        var maxRms       = 0f

        for (bar in 0 until numBars) {
            var sumSq = 0.0
            var count = 0
            // Read exactly samplesPerBar samples (or fewer if near the end of the buffer)
            while (count < samplesPerBar && buf.hasRemaining()) {
                val sample = buf.short.toFloat() / Short.MAX_VALUE
                sumSq += sample * sample
                count++
            }
            if (count > 0) {
                val rms = sqrt(sumSq / count).toFloat()
                rawEnvelope[bar] = rms
                if (rms > maxRms) maxRms = rms
            }
        }

        // Normalise so the loudest bar = 1.0. Ensures bars fill the visualiser height
        // regardless of the track's mastered loudness level.
        return if (maxRms > 0f) {
            FloatArray(numBars) { i -> (rawEnvelope[i] / maxRms).coerceIn(0f, 1f) }
        } else {
            FloatArray(numBars) { 0.1f } // safety: silent track
        }
    }
}