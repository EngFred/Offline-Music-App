package com.engfred.musicplayer.feature_dj_mix.di

import android.content.Context
import androidx.room.Room
import com.engfred.musicplayer.core.domain.BpmScanScheduler
import com.engfred.musicplayer.feature_dj_mix.data.bpm.BpmScanSchedulerImpl
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.data.local.db.AutoMixDatabase
import com.engfred.musicplayer.feature_dj_mix.data.repository.AutoMixRepositoryImpl
import com.engfred.musicplayer.feature_dj_mix.domain.repository.AutoMixRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for :features:dj_mix.
 *
 * Database version history:
 *   v1  — initial schema
 *   v2  — added firstBeatMs
 *   v3  — added amplitude
 *   v4  — added waveformEnvelope
 *   v5  — added analysisFailed
 *   v7  — cache wipe (algorithm update)
 *   v8  — cache wipe (algorithm update)
 *   v9  — cache wipe (algorithm update)
 *   v10 — cache wipe (algorithm update)
 *   v11 — cache wipe: firstBeatMs is now raw (pre-guard). See MIGRATION_10_11.
 *   v12 — (previous migration)
 *   v13 — cache wipe: firstBeatMs is now inert in the engine (songs start at 0).
 *          Avoids stale cached values confusing future tooling. BPM values are
 *          preserved conceptually but a fresh analysis gives the cleanest slate.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AutoMixModule {

    @Binds
    @Singleton
    abstract fun bindAutoMixRepository(impl: AutoMixRepositoryImpl): AutoMixRepository

    @Binds
    @Singleton
    abstract fun bindBpmScanScheduler(impl: BpmScanSchedulerImpl): BpmScanScheduler

    companion object {

        @Provides
        @Singleton
        fun provideDjMixDatabase(@ApplicationContext context: Context): AutoMixDatabase =
            Room.databaseBuilder(context, AutoMixDatabase::class.java, "dj_mix_db")
                .addMigrations(
                    AutoMixDatabase.MIGRATION_1_2,
                    AutoMixDatabase.MIGRATION_2_3,
                    AutoMixDatabase.MIGRATION_3_4,
                    AutoMixDatabase.MIGRATION_4_5,
                    AutoMixDatabase.MIGRATION_5_7,
                    AutoMixDatabase.MIGRATION_7_8,
                    AutoMixDatabase.MIGRATION_8_9,
                    AutoMixDatabase.MIGRATION_9_10,
                    AutoMixDatabase.MIGRATION_10_11,
                    AutoMixDatabase.MIGRATION_11_12,
                    AutoMixDatabase.MIGRATION_12_13, // cache wipe: firstBeatMs inert, songs start at 0
                )
                .fallbackToDestructiveMigration(true)
                .build()

        @Provides
        @Singleton
        fun provideBpmCacheDao(database: AutoMixDatabase): BpmCacheDao =
            database.bpmCacheDao()
    }
}