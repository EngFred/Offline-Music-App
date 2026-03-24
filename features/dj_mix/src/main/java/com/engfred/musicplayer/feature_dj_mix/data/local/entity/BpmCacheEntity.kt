package com.engfred.musicplayer.feature_dj_mix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity that caches the BPM result for a single audio file.
 *
 * Intentionally lives inside :features:dj_mix — BPM is computed/cached data,
 * not a MediaStore property, so it must NOT be added to [AudioFile].
 *
 * [audioFileId] matches [AudioFile.id] (MediaStore _ID).
 * [bpm] result from TarsosDSP onset-based analysis.
 * [analyzedAt] epoch-ms timestamp; lets us re-analyse stale entries in future.
 *
 * NEW (Step 1): [firstBeatMs] = timestamp in milliseconds of the very first detected onset.
 * This lets us cue the next track exactly on the first beat (true DJ-style drop).
 */
@Entity(tableName = "bpm_cache")
data class BpmCacheEntity(
    @PrimaryKey val audioFileId: Long,
    val bpm: Float,
    val analyzedAt: Long,
    /** Timestamp (ms) of the first detected onset / beat. 0 = not yet analysed. */
    val firstBeatMs: Long = 0L
)