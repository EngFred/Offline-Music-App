package com.engfred.musicplayer.core.data.audio.eq

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.model.AudioPreset
import java.nio.ByteBuffer
import javax.inject.Inject

@UnstableApi
class BandEqAudioProcessor @Inject constructor() : BaseAudioProcessor() {

    companion object {
        const val NUM_BANDS = 10
        private const val Q = 1.41
        private const val MAX_GAIN_DB =  12.0
        private const val MIN_GAIN_DB = -12.0
    }

    @Volatile private var pendingGains: DoubleArray = DoubleArray(NUM_BANDS)
    @Volatile private var gainsChanged = false

    /** NEW: Allows the Crossfade Engine to capture the current EQ state before modifying it */
    fun getGains(): DoubleArray {
        return pendingGains.clone()
    }

    fun setPreset(preset: AudioPreset) {
        setGains(EqPresets.forPreset(preset))
    }

    fun setGains(gainDb: DoubleArray) {
        require(gainDb.size == NUM_BANDS) { "Expected $NUM_BANDS gains, got ${gainDb.size}" }
        pendingGains = DoubleArray(NUM_BANDS) { gainDb[it].coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }
        gainsChanged = true
    }

    private var filters: Array<Array<BiquadPeakingFilter>> = emptyArray()

    private fun buildFilters(sampleRate: Int, channelCount: Int, gains: DoubleArray) {
        filters = Array(channelCount) {
            Array(NUM_BANDS) { band ->
                BiquadPeakingFilter().apply {
                    configure(
                        fc     = EqPresets.ISO_BAND_HZ[band],
                        gainDb = gains[band],
                        q      = Q,
                        fs     = sampleRate.toDouble()
                    )
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun applyPendingGainsIfNeeded() {
        if (!gainsChanged) return
        gainsChanged = false
        val gains = pendingGains
        val fmt   = inputAudioFormat
        if (fmt.sampleRate > 0 && fmt.channelCount > 0) {
            buildFilters(fmt.sampleRate, fmt.channelCount, gains)
            filters.forEach { ch -> ch.forEach { it.reset() } }
        }
    }

    override fun isActive(): Boolean = super.isActive()

    @OptIn(UnstableApi::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> {
                buildFilters(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, pendingGains)
                inputAudioFormat
            }
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
    }

    override fun onFlush() {
        filters.forEach { ch -> ch.forEach { it.reset() } }
    }

    override fun onReset() {
        filters = emptyArray()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        applyPendingGainsIfNeeded()

        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val output = replaceOutputBuffer(remaining)
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, output)
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, output)
        }
        output.flip()
    }

    private fun processFloat(input: ByteBuffer, output: ByteBuffer) {
        val chCount = inputAudioFormat.channelCount
        while (input.hasRemaining()) {
            for (ch in 0 until chCount) {
                if (!input.hasRemaining()) break
                output.putFloat(applyFilterChain(ch, input.float).coerceIn(-1f, 1f))
            }
        }
    }

    private fun processPcm16(input: ByteBuffer, output: ByteBuffer) {
        val chCount = inputAudioFormat.channelCount
        while (input.hasRemaining()) {
            for (ch in 0 until chCount) {
                if (!input.hasRemaining()) break
                val sample    = input.short.toFloat() / Short.MAX_VALUE
                val processed = applyFilterChain(ch, sample).coerceIn(-1f, 1f)
                output.putShort((processed * Short.MAX_VALUE).toInt().toShort())
            }
        }
    }

    private fun applyFilterChain(channel: Int, sample: Float): Float {
        if (filters.isEmpty() || channel >= filters.size) return sample
        var s = sample
        val chFilters = filters[channel]
        for (band in 0 until NUM_BANDS) s = chFilters[band].process(s)
        return s
    }
}