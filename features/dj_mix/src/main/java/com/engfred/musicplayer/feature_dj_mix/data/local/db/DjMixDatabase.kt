package com.engfred.musicplayer.feature_dj_mix.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.data.local.entity.BpmCacheEntity

/**
 * Isolated Room database for the :features:dj_mix module.
 *
 * Deliberately separate from the playlist DB — this module must not
 * pollute existing modules with its own persistence concerns.
 *
 * Version history:
 * 1 — initial schema (bpm_cache table)
 * 2 — added firstBeatMs column (for smart first-beat cueing)
 * 3 — added amplitude column (for Auto-Gain volume normalization)
 */
@Database(
    entities = [BpmCacheEntity::class],
    version = 3,
    exportSchema = false
)
abstract class DjMixDatabase : RoomDatabase() {
    abstract fun bpmCacheDao(): BpmCacheDao

    companion object {
        /**
         * Migration from version 1 to 2:
         * Adds the firstBeatMs column with default 0 (so existing rows are safe).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bpm_cache ADD COLUMN firstBeatMs INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Migration from version 2 to 3:
         * Adds the amplitude column for RMS Loudness with a default of 0.0 (REAL in SQLite).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bpm_cache ADD COLUMN amplitude REAL NOT NULL DEFAULT 0.0"
                )
            }
        }
    }
}