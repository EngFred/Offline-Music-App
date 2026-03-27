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

/**
 * ExoPlayer [AudioProcessor] backed by RubberBand for music-quality time-stretching.
 *
 * Audio path: PCM_16BIT input → float[] (RubberBand JNI) → PCM_16BIT output.
 * At stretchRatio = 1.0 RubberBand is a near-transparent pass-through (~11 ms latency).
 *
 * Thread safety: [setTimeRatio] writes a @Volatile field; the new ratio is applied at the
 * START of [queueInput] on ExoPlayer's audio thread — never mid-buffer.
 *
 * [isActive] returns true whenever a native handle is allocated, keeping the processor
 * permanently in ExoPlayer's audio chain so live ratio changes work without reconfiguration.
 *
 * RubberBand options (set in rubberband_processor.cpp):
 *   OptionProcessRealTime      — streaming mode, minimal look-ahead
 *   OptionPitchHighConsistency — preserves pitch during ratio changes
 *   OptionWindowShort          — ~11 ms latency at 44 100 Hz
 */
@OptIn(UnstableApi::class)
class RubberBandAudioProcessor : AudioProcessor {

    companion object {
        private const val TAG = "RubberBandProcessor"
        private const val RATIO_THRESHOLD = 0.001 // skip native call for negligible delta

        init { System.loadLibrary("rubber_stretcher") }
    }

    private var nativeHandle: Long = 0L
    private var configuredFormat: AudioFormat = AudioFormat.NOT_SET

    // pendingRatio is written from any thread; applied at the start of queueInput.
    @Volatile private var pendingRatio: Double = 1.0
    private var appliedRatio: Double = 1.0

    private val outputQueue = ArrayDeque<ByteBuffer>()
    private var inputEnded = false
    private var streamEnded = false

    // ── Public control ────────────────────────────────────────────────────────

    /** Time-stretch ratio: < 1.0 speeds up; > 1.0 slows down. Coerced to [0.5, 2.0]. */
    fun setTimeRatio(ratio: Double) { pendingRatio = ratio.coerceIn(0.5, 2.0) }
    fun resetRatio() { pendingRatio = 1.0 }
    fun currentRatio(): Double = appliedRatio

    // ── AudioProcessor interface ──────────────────────────────────────────────

    @Throws(UnhandledAudioFormatException::class)
    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        // Tear down previous native instance if format changed.
        if (nativeHandle != 0L) { nativeDelete(nativeHandle); nativeHandle = 0L }
        nativeHandle = nativeCreate(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        configuredFormat = inputAudioFormat
        outputQueue.clear()
        inputEnded = false; streamEnded = false
        appliedRatio = 1.0; pendingRatio = 1.0
        Log.d(TAG, "configure sr=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount}")
        return inputAudioFormat // output format identical to input
    }

    /** Always true once configured — ensures dynamic ratio changes work without pipeline rebuild. */
    override fun isActive(): Boolean = nativeHandle != 0L

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (nativeHandle == 0L || !inputBuffer.hasRemaining()) return
        applyPendingRatio()

        val ch = configuredFormat.channelCount
        val shortCount = inputBuffer.remaining() / 2
        val frameCount = shortCount / ch
        if (frameCount == 0) { inputBuffer.position(inputBuffer.limit()); return }

        // Convert PCM_16BIT (little-endian) → float[]
        val shorts = ShortArray(shortCount)
        inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        inputBuffer.position(inputBuffer.limit()) // mark consumed

        nativeProcess(nativeHandle, FloatArray(shortCount) { i -> shorts[i] / 32768f }, frameCount, false)
        drainNativeToQueue()
    }

    override fun queueEndOfStream() {
        if (nativeHandle == 0L || inputEnded) return
        inputEnded = true
        applyPendingRatio()
        nativeProcess(nativeHandle, FloatArray(0), 0, true) // flush RubberBand
        drainNativeToQueue()
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
        if (nativeHandle != 0L) nativeReset(nativeHandle)
        outputQueue.clear(); inputEnded = false; streamEnded = false
        // Re-apply whatever ratio is pending after the seek/flush.
        appliedRatio = pendingRatio
        if (nativeHandle != 0L) nativeSetTimeRatio(nativeHandle, appliedRatio)
    }

    override fun reset() {
        if (nativeHandle != 0L) { nativeDelete(nativeHandle); nativeHandle = 0L }
        outputQueue.clear()
        configuredFormat = AudioFormat.NOT_SET
        inputEnded = false; streamEnded = false; appliedRatio = 1.0; pendingRatio = 1.0
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun applyPendingRatio() {
        val r = pendingRatio
        if (Math.abs(r - appliedRatio) > RATIO_THRESHOLD) {
            nativeSetTimeRatio(nativeHandle, r)
            appliedRatio = r
        }
    }

    /** Pull all available frames out of RubberBand and store as PCM_16BIT ByteBuffers. */
    private fun drainNativeToQueue() {
        if (nativeHandle == 0L) return
        var avail = nativeAvailable(nativeHandle)
        while (avail > 0) {
            val floats = nativeRetrieve(nativeHandle, avail) ?: break
            val buf = ByteBuffer.allocate(floats.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (f in floats) buf.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            buf.flip()
            outputQueue.addLast(buf)
            avail = nativeAvailable(nativeHandle)
        }
    }

    // ── JNI (implemented in rubberband_processor.cpp) ─────────────────────────

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeSetTimeRatio(handle: Long, ratio: Double)
    private external fun nativeProcess(handle: Long, input: FloatArray, frameCount: Int, isFinal: Boolean)
    private external fun nativeAvailable(handle: Long): Int
    private external fun nativeRetrieve(handle: Long, frameCount: Int): FloatArray?
    private external fun nativeReset(handle: Long)
    private external fun nativeDelete(handle: Long)
}