package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * ExoPlayer [AudioProcessor] backed by RubberBand for music-quality time-stretching.
 *
 * This processor sits in the audio pipeline between the AAC decoder and DefaultAudioSink.
 * The most important diagnostic signal it emits is a SECOND call to [configure] after
 * playback has already started — this is the fingerprint of an AAC DRC format change
 * that [DrcSuppressingMediaCodecAdapterFactory] should have prevented.
 *
 * Log tag "RubberBandProcessor" — search for:
 *   "configure: SECOND CONFIGURE DETECTED" → DRC suppression did NOT work on this device
 *   "configure: initial configure"          → normal first-time setup, DRC suppression working
 *
 * Architecture:
 *  - At ratio = 1.0 (no tempo sync needed): pure bypass path (no RubberBand processing).
 *    This prevents phase rotation and latency artefacts when stretching is off.
 *  - At ratio ≠ 1.0: interleaved PCM → RubberBand → output queue drain loop.
 *  - flush() re-primes the native stretcher after each seek or AudioSink reconfigure.
 *  - The nativePrewarm() call after create/flush fills the FFT windows with silence so
 *    the first real audio frames are not delayed by algorithmic latency.
 */
@OptIn(UnstableApi::class)
class RubberBandAudioProcessor : AudioProcessor {

    companion object {
        private const val TAG = "RubberBandProcessor"
        private const val RATIO_THRESHOLD = 0.001

        init { System.loadLibrary("rubber_stretcher") }
    }

    private var nativeHandle: Long = 0L
    private var configuredFormat: AudioFormat = AudioFormat.NOT_SET

    // How many times configure() has been called on this instance.
    // Expected: exactly 1. If > 1 seen in logs, DRC suppression is failing.
    private var configureCallCount = 0

    @Volatile private var pendingRatio: Double = 1.0
    private var appliedRatio: Double = 1.0

    private val outputQueue = ArrayDeque<ByteBuffer>()
    private var inputEnded = false
    private var streamEnded = false

    // ── Public control ────────────────────────────────────────────────────────

    fun setTimeRatio(ratio: Double) { pendingRatio = ratio.coerceIn(0.5, 2.0) }
    fun resetRatio() { pendingRatio = 1.0 }
    fun currentRatio(): Double = appliedRatio

    // ── AudioProcessor interface ──────────────────────────────────────────────

    @Throws(UnhandledAudioFormatException::class)
    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        configureCallCount++

        // ── KEY DIAGNOSTIC LOG ───────────────────────────────────────────────
        // A second call to configure() during active playback means the AAC
        // decoder emitted INFO_OUTPUT_FORMAT_CHANGED and ExoPlayer responded by
        // flushing and reconfiguring DefaultAudioSink — exactly the sequence that
        // causes the headphone/BT stall. DrcSuppressingMediaCodecAdapterFactory
        // should have prevented this. If you see this log, check that:
        //   1. buildExoPlayer() is passing DrcSuppressingMediaCodecAdapterFactory
        //      to ExoPlayer.Builder.setMediaCodecAdapterFactory()
        //   2. DrcSuppressingAdapter.getOutputFormat() logged
        //      "Layer1C: ✅ Suppressed DRC-only format change!" — if not, the
        //      factory is not wrapping this player's codec
        if (configureCallCount == 1) {
            Log.d(TAG, "configure: initial configure #$configureCallCount — " +
                    "sr=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} " +
                    "encoding=${inputAudioFormat.encoding}. " +
                    "DRC suppression should prevent any further calls.")
        } else {
            Log.e(TAG, "configure: ❌ SECOND CONFIGURE DETECTED (#$configureCallCount)! " +
                    "sr=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount}. " +
                    "This means DrcSuppressingMediaCodecAdapterFactory did NOT suppress the " +
                    "DRC format change on this device. The AudioTrack stall will likely occur. " +
                    "The Layer 2 stall detector in CrossfadeEngine will attempt auto-recovery. " +
                    "Check DrcSuppressingAdapter logcat for Layer1C lines.")
        }

        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (nativeHandle != 0L) {
            Log.d(TAG, "configure: deleting existing native handle before reconfigure")
            nativeDelete(nativeHandle)
            nativeHandle = 0L
        }

        nativeHandle = nativeCreate(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        Log.d(TAG, "configure: native handle created. Prewarming...")

        // Pre-warm native stretcher immediately after creation.
        // Fills FFT windows with silence to minimize output latency on the first
        // real audio frames.
        nativePrewarm(nativeHandle)
        Log.d(TAG, "configure: pre-warm complete. Handle ready.")

        configuredFormat = inputAudioFormat
        outputQueue.clear()
        inputEnded = false; streamEnded = false
        appliedRatio = 1.0; pendingRatio = 1.0
        return inputAudioFormat
    }

    override fun isActive(): Boolean = nativeHandle != 0L

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (nativeHandle == 0L || !inputBuffer.hasRemaining()) return
        applyPendingRatio()

        // Soft Bypass at Ratio 1.0:
        // When no tempo-stretching is needed, pass audio through directly without
        // going through RubberBand. Prevents phase rotation and latency artefacts.
        if (abs(appliedRatio - 1.0) < RATIO_THRESHOLD) {
            val bytes = inputBuffer.remaining()
            val bypassBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            bypassBuffer.put(inputBuffer)
            bypassBuffer.flip()
            outputQueue.addLast(bypassBuffer)
            return
        }

        val ch = configuredFormat.channelCount
        val shortCount = inputBuffer.remaining() / 2
        val frameCount = shortCount / ch
        if (frameCount == 0) { inputBuffer.position(inputBuffer.limit()); return }

        val shorts = ShortArray(shortCount)
        inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        inputBuffer.position(inputBuffer.limit())

        nativeProcess(nativeHandle, FloatArray(shortCount) { i -> shorts[i] / 32768f }, frameCount, false)
        drainNativeToQueue()
    }

    override fun queueEndOfStream() {
        if (nativeHandle == 0L || inputEnded) return
        inputEnded = true
        applyPendingRatio()

        // Only flush native if we aren't in bypass
        if (abs(appliedRatio - 1.0) >= RATIO_THRESHOLD) {
            nativeProcess(nativeHandle, FloatArray(0), 0, true)
            drainNativeToQueue()
        }

        if (outputQueue.isEmpty()) streamEnded = true
    }

    override fun getOutput(): ByteBuffer {
        if (outputQueue.isEmpty()) return AudioProcessor.EMPTY_BUFFER
        val buf = outputQueue.removeFirst()
        if (inputEnded && outputQueue.isEmpty()) streamEnded = true
        return buf
    }

    override fun isEnded(): Boolean = inputEnded && streamEnded && outputQueue.isEmpty()

    override fun flush() {
        if (nativeHandle != 0L) {
            nativeReset(nativeHandle)
            // Re-prime native stretcher after a flush/seek so the first frames
            // after the seek don't suffer from algorithm startup latency.
            nativePrewarm(nativeHandle)
            Log.d(TAG, "flush: native stretcher reset + pre-warmed. " +
                    "configureCallCount=$configureCallCount appliedRatio=$appliedRatio")
        }
        outputQueue.clear(); inputEnded = false; streamEnded = false
        appliedRatio = pendingRatio
        if (nativeHandle != 0L) nativeSetTimeRatio(nativeHandle, appliedRatio)
    }

    override fun reset() {
        Log.d(TAG, "reset: releasing native handle. configureCallCount=$configureCallCount")
        if (nativeHandle != 0L) { nativeDelete(nativeHandle); nativeHandle = 0L }
        outputQueue.clear()
        configuredFormat  = AudioFormat.NOT_SET
        configureCallCount = 0  // Reset so the next configure() starts fresh
        inputEnded        = false
        streamEnded       = false
        appliedRatio      = 1.0
        pendingRatio      = 1.0
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun applyPendingRatio() {
        val r = pendingRatio
        if (Math.abs(r - appliedRatio) > RATIO_THRESHOLD) {
            nativeSetTimeRatio(nativeHandle, r)
            appliedRatio = r
            Log.d(TAG, "applyPendingRatio: ratio changed to $r")
        }
    }

    private fun drainNativeToQueue() {
        if (nativeHandle == 0L) return
        var avail = nativeAvailable(nativeHandle)
        while (avail > 0) {
            val floats = nativeRetrieve(nativeHandle, avail) ?: break
            val buf = ByteBuffer.allocateDirect(floats.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (f in floats) buf.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            buf.flip()
            outputQueue.addLast(buf)
            avail = nativeAvailable(nativeHandle)
        }
    }

    // ── JNI ───────────────────────────────────────────────────────────────────

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeSetTimeRatio(handle: Long, ratio: Double)
    private external fun nativeProcess(handle: Long, input: FloatArray, frameCount: Int, isFinal: Boolean)
    private external fun nativeAvailable(handle: Long): Int
    private external fun nativeRetrieve(handle: Long, frameCount: Int): FloatArray?
    private external fun nativeReset(handle: Long)
    private external fun nativeDelete(handle: Long)
    private external fun nativePrewarm(handle: Long)
}