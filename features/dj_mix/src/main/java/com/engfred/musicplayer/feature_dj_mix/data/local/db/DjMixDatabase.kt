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
    version = 5,
    exportSchema = false
)
@TypeConverters(FloatArrayTypeConverter::class)
abstract class DjMixDatabase : RoomDatabase() {
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
    }
}