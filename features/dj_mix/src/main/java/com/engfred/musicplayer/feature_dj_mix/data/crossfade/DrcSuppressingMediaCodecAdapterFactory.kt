package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/**
 * Three-layer defense against the AAC DRC headphone/Bluetooth AudioTrack stall.
 *
 * ── ROOT CAUSE (confirmed by testing) ────────────────────────────────────────
 * When audio routes to headphones or Bluetooth, Android's audio framework sends
 * DRC (Dynamic Range Compression) parameters directly to the AAC MediaCodec via
 * MediaCodec.setParameters() at the OS level (~400ms into playback).
 * The AAC decoder then emits INFO_OUTPUT_FORMAT_CHANGED.
 * ExoPlayer receives this, calls AudioSink.configure() → new AudioTrack is created.
 * On BT/headphone paths the HAL initialization for the new AudioTrack races with
 * the BT codec negotiation → AudioTrack.getPlaybackHeadPosition() returns 0
 * indefinitely → ExoPlayer's write loop stalls → silence, position stuck at 0:00.
 * Speakers are NOT affected because HAL-level DRC is applied there, so the AAC
 * decoder never receives the mid-playback setParameters() call.
 *
 * ── HOW THIS CLASS FIXES IT ───────────────────────────────────────────────────
 *
 * Layer 1A — Pre-configure (MediaFormat modification before MediaCodec.configure):
 *   Sets aac-drc-effect-type=-1 and related keys in the MediaFormat BEFORE the
 *   codec is configured. Tells the decoder to start with DRC disabled so the
 *   framework has nothing to renegotiate.
 *
 * Layer 1B — setParameters() interception:
 *   Blocks any ExoPlayer-initiated DRC parameter changes from reaching the codec.
 *   Note: Android's framework calls MediaCodec.setParameters() directly (not via
 *   the adapter), so this layer alone is insufficient — it is a belt-and-suspenders
 *   guard for ExoPlayer-sourced DRC changes only.
 *
 * Layer 1C — getOutputFormat() interception (THE CRITICAL LAYER):
 *   Even if the decoder still emits INFO_OUTPUT_FORMAT_CHANGED after Layers 1A/1B,
 *   we intercept getOutputFormat() and return the ORIGINAL format when only DRC
 *   metadata changed (sample rate and channel count are unchanged).
 *   ExoPlayer's MediaCodecAudioRenderer therefore sees NO format change →
 *   AudioSink.configure() is never called → AudioTrack is never recreated →
 *   no HAL initialization race → no stall.
 *
 * Only AAC decoders are wrapped. All other codecs pass through unchanged.
 */
@OptIn(UnstableApi::class)
class DrcSuppressingMediaCodecAdapterFactory : MediaCodecAdapter.Factory {

    private val delegate = DefaultMediaCodecAdapterFactory.DEFAULT

    companion object {
        private const val TAG = "DrcSuppressingFactory"

        // AAC MIME types that require DRC suppression
        private val AAC_MIME_TYPES = setOf(
            "audio/mp4a-latm",
            "audio/aac",
            "audio/mpeg4-generic"
        )

        // DRC parameter keys — these are the ones that trigger INFO_OUTPUT_FORMAT_CHANGED
        private val DRC_PARAM_KEYS = setOf(
            "aac-drc-effect-type",
            "aac-target-ref-level",
            "aac-drc-heavy-compression",
            "aac-drc-boost-level",
            "aac-drc-cut-level",
            "aac-drc-output-loudness",
            "aac-drc-album-mode"
        )
    }

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        // Determine MIME type from ExoPlayer's Format (reliable, always present)
        val mimeType = configuration.format.sampleMimeType ?: ""
        val isAac = AAC_MIME_TYPES.any { it.equals(mimeType, ignoreCase = true) }

        if (isAac) {
            // ── Layer 1A: Pre-configure DRC params in the MediaFormat ─────────
            // This runs BEFORE MediaCodec.configure() so the decoder starts in a
            // DRC-off state. If the framework sees the decoder already has the
            // "correct" DRC setting, it may skip the mid-playback setParameters().
            try {
                configuration.mediaFormat.setInteger("aac-drc-effect-type", -1)
                configuration.mediaFormat.setInteger("aac-target-ref-level", -1)
                configuration.mediaFormat.setInteger("aac-drc-heavy-compression", 0)
                configuration.mediaFormat.setInteger("aac-drc-boost-level", 127)   // max = no boost cut
                configuration.mediaFormat.setInteger("aac-drc-cut-level", 127)     // max = no cut
                Log.d(TAG, "Layer1A: Pre-configured AAC DRC params for MIME=$mimeType. " +
                        "aac-drc-effect-type=-1 aac-target-ref-level=-1 aac-drc-heavy-compression=0")
            } catch (e: Exception) {
                // Non-fatal — Layer 1C will catch any fallthrough
                Log.w(TAG, "Layer1A: Failed to pre-configure DRC params (non-fatal): ${e.message}")
            }
        }

        val adapted = delegate.createAdapter(configuration)

        return if (isAac) {
            Log.d(TAG, "Wrapping AAC adapter with DrcSuppressedAdapter for MIME=$mimeType")
            DrcSuppressedAdapter(adapted)
        } else {
            adapted
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner shim — wraps the real adapter for AAC decoders only
    // ─────────────────────────────────────────────────────────────────────────

    private class DrcSuppressedAdapter(
        private val inner: MediaCodecAdapter
    ) : MediaCodecAdapter by inner {

        // The format reported to ExoPlayer after the FIRST INFO_OUTPUT_FORMAT_CHANGED.
        // Subsequent DRC-only changes return this same format so ExoPlayer sees no change.
        @Volatile private var anchoredOutputFormat: MediaFormat? = null

        // ── Layer 1B: Block DRC setParameters from the ExoPlayer adapter path ─
        // Android's framework calls MediaCodec.setParameters() directly, so this
        // layer only catches ExoPlayer-initiated DRC changes (rare but possible).
        override fun setParameters(params: Bundle) {
            val hasDrcKey = params.keySet().any { it in DRC_PARAM_KEYS }
            if (hasDrcKey) {
                Log.w(TAG, "Layer1B: Suppressed DRC setParameters call. " +
                        "keys=${params.keySet().filter { it in DRC_PARAM_KEYS }}")
                return  // Do NOT forward — prevents ExoPlayer from re-enabling DRC
            }
            inner.setParameters(params)
        }

        // ── Layer 1C: Suppress DRC-only format changes from reaching ExoPlayer ─
        // This is the CRITICAL layer. When the Android framework successfully
        // updates the AAC decoder's DRC params via a direct MediaCodec.setParameters()
        // call (which bypasses our Layer 1B), the decoder emits
        // INFO_OUTPUT_FORMAT_CHANGED. ExoPlayer then calls getOutputFormat() here.
        // If we return the ORIGINAL format (same sample rate + channel count),
        // ExoPlayer's MediaCodecAudioRenderer sees no meaningful change and does
        // NOT call AudioSink.configure() → no new AudioTrack → no HAL init race.
        override fun getOutputFormat(): MediaFormat {
            val current = inner.getOutputFormat()
            val anchored = anchoredOutputFormat

            if (anchored == null) {
                // First call — anchor this format and report it normally.
                anchoredOutputFormat = current
                Log.d(TAG, "Layer1C: Anchored initial AAC output format: " +
                        "sr=${current.getIntSafe(MediaFormat.KEY_SAMPLE_RATE)} " +
                        "ch=${current.getIntSafe(MediaFormat.KEY_CHANNEL_COUNT)}")
                return current
            }

            val anchorSr  = anchored.getIntSafe(MediaFormat.KEY_SAMPLE_RATE)
            val anchorCh  = anchored.getIntSafe(MediaFormat.KEY_CHANNEL_COUNT)
            val currentSr = current.getIntSafe(MediaFormat.KEY_SAMPLE_RATE)
            val currentCh = current.getIntSafe(MediaFormat.KEY_CHANNEL_COUNT)

            return if (currentSr == anchorSr && currentCh == anchorCh) {
                // ✅ DRC-only change detected — sample rate and channel count unchanged.
                // Return the anchored format so ExoPlayer sees NO format change.
                // AudioSink.configure() will NOT be called. AudioTrack stays alive.
                Log.w(TAG, "Layer1C: ✅ Suppressed DRC-only format change! " +
                        "sr=$currentSr (unchanged) ch=$currentCh (unchanged). " +
                        "AudioSink reconfigure PREVENTED — no AudioTrack recreation.")
                anchored
            } else {
                // ❌ Real format change (sample rate or channel count actually changed).
                // This is a legitimate change — allow it through and re-anchor.
                Log.d(TAG, "Layer1C: Real format change — forwarding. " +
                        "sr: $anchorSr→$currentSr, ch: $anchorCh→$currentCh")
                anchoredOutputFormat = current
                current
            }
        }

        private fun MediaFormat.getIntSafe(key: String, default: Int = 0): Int =
            try { getInteger(key) } catch (_: Exception) { default }
    }
}