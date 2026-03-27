package com.engfred.musicplayer.feature_dj_mix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity that caches the BPM result for a single audio file.
 *
 * [waveformEnvelope] is a downsampled RMS amplitude envelope computed from the
 * raw PCM during BPM analysis. 128 normalised floats (0.0–1.0), each representing
 * one time slice of the track. Stored via [FloatArrayTypeConverter]. Empty array
 * = not yet analysed; the engine falls back to the synthetic pattern in that case.
 *
 * Version history:
 * 1 — initial schema
 * 2 — added firstBeatMs
 * 3 — added amplitude
 * 4 — added waveformEnvelope
 * 5 — added analysisFailed
 * 6 — added customCueInMs and customMixOutMs                          ← NEW
 */
@Entity(tableName = "bpm_cache")
data class BpmCacheEntity(
    @PrimaryKey val audioFileId: Long,
    val bpm: Float,
    val analyzedAt: Long,
    val firstBeatMs: Long = 0L,
    val amplitude: Float = 0f,
    val waveformEnvelope: FloatArray = FloatArray(0),
    val analysisFailed: Boolean = false,

    // ── User Overrides ──
    val customCueInMs: Long? = null,
    val customMixOutMs: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BpmCacheEntity) return false
        return audioFileId == other.audioFileId &&
                bpm == other.bpm &&
                analyzedAt == other.analyzedAt &&
                firstBeatMs == other.firstBeatMs &&
                amplitude == other.amplitude &&
                waveformEnvelope.contentEquals(other.waveformEnvelope) &&
                analysisFailed == other.analysisFailed &&
                customCueInMs == other.customCueInMs &&
                customMixOutMs == other.customMixOutMs
    }

    override fun hashCode(): Int {
        var result = audioFileId.hashCode()
        result = 31 * result + bpm.hashCode()
        result = 31 * result + analyzedAt.hashCode()
        result = 31 * result + firstBeatMs.hashCode()
        result = 31 * result + amplitude.hashCode()
        result = 31 * result + waveformEnvelope.contentHashCode()
        result = 31 * result + analysisFailed.hashCode()
        result = 31 * result + (customCueInMs?.hashCode() ?: 0)
        result = 31 * result + (customMixOutMs?.hashCode() ?: 0)
        return result
    }
}