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
 * 🚨 PRO-GRADE AUDIO PIPELINE 🚨
 * Zero-Copy AudioStretcher bridging ExoPlayer to native RubberBand C++.
 * Uses direct memory access with exact byte offsets to prevent JNI bottlenecks
 * and dead-memory reads.
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

    private var configureCallCount = 0

    @Volatile private var pendingRatio: Double = 1.0
    private var appliedRatio: Double = 1.0

    private var inputEnded = false
    private var streamEnded = false

    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

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

        Log.i(TAG, "[SETUP] RubberBand Configured (Call #$configureCallCount)")
        return inputAudioFormat
    }

    override fun isActive(): Boolean = nativeHandle != 0L

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (nativeHandle == 0L || !inputBuffer.hasRemaining()) return
        applyPendingRatio()

        val byteOffset = inputBuffer.position()
        val byteLimit = inputBuffer.limit()
        val bytesAvailable = byteLimit - byteOffset

        val ch = configuredFormat.channelCount
        val frameCount = bytesAvailable / (ch * 2)

        if (frameCount == 0) {
            inputBuffer.position(byteLimit)
            return
        }

        // Pass raw memory + the exact byte offset directly to C++
        nativeProcess(nativeHandle, inputBuffer, byteOffset, frameCount, false)

        // Mark buffer as completely consumed by ExoPlayer
        inputBuffer.position(byteLimit)
        drainNative()
    }

    override fun queueEndOfStream() {
        if (nativeHandle == 0L || inputEnded) return
        inputEnded = true
        applyPendingRatio()

        // Tell C++ to flush its latency buffers
        nativeProcess(nativeHandle, null, 0, 0, true)
        drainNative()
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER

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
        val availFrames = nativeAvailable(nativeHandle)

        if (availFrames > 0) {
            val ch = configuredFormat.channelCount
            val requiredBytes = availFrames * ch * 2
            val outBuf = replaceOutputBuffer(requiredBytes)

            // Pass the allocated memory space to C++ to fill directly
            val framesRetrieved = nativeRetrieve(nativeHandle, outBuf, availFrames)

            if (framesRetrieved > 0) {
                outBuf.position(0)
                outBuf.limit(framesRetrieved * ch * 2)
            } else {
                outBuf.position(0)
                outBuf.limit(0)
            }
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
    // Signature updated to include byteOffset
    private external fun nativeProcess(handle: Long, inputBuffer: ByteBuffer?, byteOffset: Int, frameCount: Int, isFinal: Boolean)
    private external fun nativeAvailable(handle: Long): Int
    private external fun nativeRetrieve(handle: Long, outputBuffer: ByteBuffer, maxFrames: Int): Int
    private external fun nativeReset(handle: Long)
    private external fun nativeDelete(handle: Long)
    private external fun nativePrewarm(handle: Long)
}