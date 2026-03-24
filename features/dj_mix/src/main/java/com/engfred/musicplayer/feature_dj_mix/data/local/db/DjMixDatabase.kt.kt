package com.engfred.musicplayer.feature_dj_mix.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.data.local.entity.BpmCacheEntity

/**
 * Isolated Room database for the :features:dj_mix module.
 *
 * Deliberately separate from the playlist DB — this module must not
 * pollute existing modules with its own persistence concerns.
 *
 * Version history:
 *   1 — initial schema (bpm_cache table)
 */
@Database(
    entities = [BpmCacheEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DjMixDatabase : RoomDatabase() {
    abstract fun bpmCacheDao(): BpmCacheDao
}