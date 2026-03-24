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
 * [bpm]         result from TarsosDSP onset-based analysis.
 * [analyzedAt]  epoch-ms timestamp; lets us re-analyse stale entries in future.
 */
@Entity(tableName = "bpm_cache")
data class BpmCacheEntity(
    @PrimaryKey val audioFileId: Long,
    val bpm: Float,
    val analyzedAt: Long
)