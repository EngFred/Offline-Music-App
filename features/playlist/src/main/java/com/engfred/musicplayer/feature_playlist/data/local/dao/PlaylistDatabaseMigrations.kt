package com.engfred.musicplayer.feature_playlist.data.local.dao

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Provides Room database migration objects.
 * Each object defines how to migrate the database schema from one version to the next.
 */
object PlaylistDatabaseMigrations {

    /**
     * Migration from database version 1 to version 2.
     * This migration:
     * 1. Adds the `song_play_events` table for tracking song play counts.
     * 2. Adds `isAutomatic` and `type` columns to the existing `playlists` table.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. Create the new 'song_play_events' table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `song_play_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `audioFileId` INTEGER NOT NULL,
                    `timestamp` INTEGER NOT NULL
                )
            """)
            // Create an index on 'audioFileId' and 'timestamp' for efficient querying
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_song_play_events_audioFileId_timestamp`
                ON `song_play_events` (`audioFileId`, `timestamp`)
            """)

            // 2. Add new columns to the existing 'playlists' table
            // Add 'isAutomatic' column with a default value of 0 (false)
            db.execSQL("""
                ALTER TABLE `playlists` ADD COLUMN `isAutomatic` INTEGER NOT NULL DEFAULT 0
            """)
            // Add 'type' column with a default value of NULL
            db.execSQL("""
                ALTER TABLE `playlists` ADD COLUMN `type` TEXT DEFAULT NULL
            """)
        }
    }
}