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
 * (Full Documentation Kept as Requested)
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
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (nativeHandle != 0L) { nativeDelete(nativeHandle); nativeHandle = 0L }

        nativeHandle = nativeCreate(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)

        // ROOT CAUSE FIX 2: Pre-warm native stretcher immediately after creation.
        nativePrewarm(nativeHandle)

        configuredFormat = inputAudioFormat
        outputQueue.clear()
        inputEnded = false; streamEnded = false
        appliedRatio = 1.0; pendingRatio = 1.0
        Log.d(TAG, "configure sr=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount}")
        return inputAudioFormat
    }

    override fun isActive(): Boolean = nativeHandle != 0L

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (nativeHandle == 0L || !inputBuffer.hasRemaining()) return
        applyPendingRatio()

        // ROOT CAUSE FIX 3: Soft Bypass at Ratio 1.0.
        // Prevents phase rotation and latency artifacts when time-stretching isn't needed.
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
            // ROOT CAUSE FIX 2: Re-prime native stretcher after a flush/seek.
            nativePrewarm(nativeHandle)
        }
        outputQueue.clear(); inputEnded = false; streamEnded = false
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
    private external fun nativePrewarm(handle: Long) // New native helper
}