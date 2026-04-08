package com.engfred.musicplayer.feature_dj_mix.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.engfred.musicplayer.feature_dj_mix.data.local.converter.FloatArrayTypeConverter
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.data.local.entity.BpmCacheEntity

@Database(
    entities = [BpmCacheEntity::class],
    version = 11,
    exportSchema = false
)
@TypeConverters(FloatArrayTypeConverter::class)
abstract class AutoMixDatabase : RoomDatabase() {
    abstract fun bpmCacheDao(): BpmCacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bpm_cache ADD COLUMN firstBeatMs INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bpm_cache ADD COLUMN amplitude REAL NOT NULL DEFAULT 0.0")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bpm_cache ADD COLUMN waveformEnvelope TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bpm_cache ADD COLUMN analysisFailed INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_5_7 = object : Migration(5, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM bpm_cache")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM bpm_cache")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM bpm_cache")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM bpm_cache")
            }
        }

        /**
         * Version 10 → 11: Wipe BPM cache so all tracks are re-analysed.
         *
         * WHY this wipe is necessary:
         *   In version ≤ 10, [BpmCacheEntity.firstBeatMs] stored the GUARDED value —
         *   i.e. the raw aubio beat-0 had already been phase-advanced to clear the
         *   hardcoded 15-second minimum offset before being written to the database.
         *
         *   As of version 11, [BpmCacheEntity.firstBeatMs] stores the RAW aubio
         *   beat-0 (after beat-snap and onset offset, but WITHOUT the minimum-offset
         *   guard). The guard is now applied dynamically at runtime by
         *   CrossfadeEngine.applyFirstBeatGuard() using the user's configurable
         *   cue-point setting (0–30 s, default 15 s).
         *
         *   Any cached entry from version ≤ 10 carries the old 15-second-guarded
         *   value, which would be incorrectly treated as a raw beat-0 and then
         *   guarded AGAIN, resulting in a firstBeatMs that is ~15 s too late.
         *
         *   Wiping the cache forces a full re-analysis pass on the user's next
         *   session start, after which all entries are stored in the new raw format.
         *
         * Impact on the user:
         *   BPM analysis will run again on first launch after the update, exactly
         *   as it did when the user first installed the app. The queue ordering
         *   and mix quality are unaffected.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Wipe cache — entries stored the old guarded firstBeatMs value.
                // Re-analysis will store raw firstBeatMs going forward.
                db.execSQL("DELETE FROM bpm_cache")
            }
        }
    }
}