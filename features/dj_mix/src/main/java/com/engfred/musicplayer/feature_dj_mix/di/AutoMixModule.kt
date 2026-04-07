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
 * Wires:
 * - [AutoMixDatabase]         — Room database (BPM cache).
 * - [BpmCacheDao]           — DAO from the database.
 * - [AutoMixRepository]       — bound to [AutoMixRepositoryImpl].
 *
 * Auto-provided by Hilt (no manual @Provides needed):
 * - [BpmAnalyzer]           — @Singleton @Inject constructor.
 * - [BpmAnalysisWorker]     — @HiltWorker, handled by hilt-work integration.
 * - [CrossfadeEngine]       — @Singleton @Inject constructor.
 * - [DjSessionManager]      — @Singleton @Inject constructor.
 * - [GetSmartNextTrackUseCase] — @Inject constructor, stateless.
 * - [AnalyzeBpmUseCase]     — @Inject constructor, delegates to repository.
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
                    AutoMixDatabase.MIGRATION_7_8
                )
                .fallbackToDestructiveMigration(true)
                .build()

        @Provides
        @Singleton
        fun provideBpmCacheDao(database: AutoMixDatabase): BpmCacheDao =
            database.bpmCacheDao()
    }
}