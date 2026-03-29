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
 * 🚨 CRITICAL AUDIO PIPELINE COMPONENT 🚨
 * * Zero-Allocation AudioStretcher bridging ExoPlayer to native RubberBand C++.
 * Always use `replaceOutputBuffer(size)` to allocate bytes. Never instantiate
 * ByteBuffers manually during playback. The ExoPlayer AudioSink holds references
 * to these buffers; manual allocation causes terrible screeching noise and GC pauses.
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

    // Diagnostic tool: Checks if DRC Suppressor is failing (should only equal 1)
    private var configureCallCount = 0

    @Volatile private var pendingRatio: Double = 1.0
    private var appliedRatio: Double = 1.0

    private var inputEnded = false
    private var streamEnded = false

    // ExoPlayer BaseAudioProcessor memory pattern
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    // Re-used float array to prevent allocations during JNI handoff
    private var inputFloats = FloatArray(0)

    fun setTimeRatio(ratio: Double) { pendingRatio = ratio.coerceIn(0.5, 2.0) }
    fun resetRatio() { pendingRatio = 1.0 }
    fun currentRatio(): Double = appliedRatio

    @Throws(UnhandledAudioFormatException::class)
    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        configureCallCount++

        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (nativeHandle != 0L) {
            nativeDelete(nativeHandle)
            nativeHandle = 0L
        }

        nativeHandle = nativeCreate(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        nativePrewarm(nativeHandle)

        configuredFormat = inputAudioFormat
        inputEnded = false
        streamEnded = false
        appliedRatio = 1.0
        pendingRatio = 1.0

        Log.i(TAG, "[SETUP] RubberBand Configured (Call #$configureCallCount)")
        return inputAudioFormat
    }

    override fun isActive(): Boolean = nativeHandle != 0L

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (nativeHandle == 0L || !inputBuffer.hasRemaining()) return
        applyPendingRatio()

        // Bypass Mode - Direct memory copy, avoids JNI overhead and phase issues
        if (abs(appliedRatio - 1.0) < RATIO_THRESHOLD) {
            val bytes = inputBuffer.remaining()
            val outBuf = replaceOutputBuffer(bytes)
            outBuf.put(inputBuffer)
            outBuf.flip()
            return
        }

        val ch = configuredFormat.channelCount
        val shortCount = inputBuffer.remaining() / 2
        val frameCount = shortCount / ch
        if (frameCount == 0) { inputBuffer.position(inputBuffer.limit()); return }

        // Reuse FloatArray to prevent GC pauses
        if (inputFloats.size < shortCount) inputFloats = FloatArray(shortCount)

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            inputFloats[i] = inputBuffer.getShort() / 32768f
        }

        nativeProcess(nativeHandle, inputFloats, frameCount, false)
        drainNative()
    }

    override fun queueEndOfStream() {
        if (nativeHandle == 0L || inputEnded) return
        inputEnded = true
        applyPendingRatio()

        if (abs(appliedRatio - 1.0) >= RATIO_THRESHOLD) {
            nativeProcess(nativeHandle, FloatArray(0), 0, true)
            drainNative()
        }
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER

        // Signal EOS once ExoPlayer drains the last buffer
        if (inputEnded && out === AudioProcessor.EMPTY_BUFFER) {
            streamEnded = true
        }
        return out
    }

    override fun isEnded(): Boolean = inputEnded && streamEnded

    override fun flush() {
        if (nativeHandle != 0L) {
            nativeReset(nativeHandle)
            nativePrewarm(nativeHandle)
        }
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        streamEnded = false
        appliedRatio = pendingRatio
        if (nativeHandle != 0L) nativeSetTimeRatio(nativeHandle, appliedRatio)
    }

    override fun reset() {
        if (nativeHandle != 0L) { nativeDelete(nativeHandle); nativeHandle = 0L }
        configuredFormat  = AudioFormat.NOT_SET
        configureCallCount = 0
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        buffer = AudioProcessor.EMPTY_BUFFER
        inputEnded        = false
        streamEnded       = false
        appliedRatio      = 1.0
        pendingRatio      = 1.0
    }

    private fun applyPendingRatio() {
        val r = pendingRatio
        if (Math.abs(r - appliedRatio) > RATIO_THRESHOLD) {
            nativeSetTimeRatio(nativeHandle, r)
            appliedRatio = r
        }
    }

    private fun drainNative() {
        if (nativeHandle == 0L) return
        val avail = nativeAvailable(nativeHandle)
        if (avail > 0) {
            val floats = nativeRetrieve(nativeHandle, avail) ?: return
            val bytes = floats.size * 2
            val outBuf = replaceOutputBuffer(bytes)

            for (f in floats) {
                outBuf.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            outBuf.flip()
        }
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
        return buffer
    }

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeSetTimeRatio(handle: Long, ratio: Double)
    private external fun nativeProcess(handle: Long, input: FloatArray, frameCount: Int, isFinal: Boolean)
    private external fun nativeAvailable(handle: Long): Int
    private external fun nativeRetrieve(handle: Long, frameCount: Int): FloatArray?
    private external fun nativeReset(handle: Long)
    private external fun nativeDelete(handle: Long)
    private external fun nativePrewarm(handle: Long)
}