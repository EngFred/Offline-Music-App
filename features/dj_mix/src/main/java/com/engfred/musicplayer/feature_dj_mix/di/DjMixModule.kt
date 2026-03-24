package com.engfred.musicplayer.feature_dj_mix.di

import android.content.Context
import androidx.room.Room
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.data.local.db.DjMixDatabase
import com.engfred.musicplayer.feature_dj_mix.data.repository.DjMixRepositoryImpl
import com.engfred.musicplayer.feature_dj_mix.domain.repository.DjMixRepository
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
 * - [DjMixDatabase]         — Room database (BPM cache).
 * - [BpmCacheDao]           — DAO from the database.
 * - [DjMixRepository]       — bound to [DjMixRepositoryImpl].
 *
 * Not wired here (auto-provided by Hilt):
 * - [BpmAnalyzer]           — @Singleton @Inject constructor, no manual @Provides needed.
 * - [BpmAnalysisWorker]     — @HiltWorker, handled by hilt-work integration.
 * - [CrossfadeEngine]       — @Inject constructor, non-singleton; each ViewModel gets its own.
 * - [GetSmartNextTrackUseCase] — @Inject constructor, stateless.
 * - [AnalyzeBpmUseCase]     — @Inject constructor, delegates to repository.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DjMixModule {

    @Binds
    @Singleton
    abstract fun bindDjMixRepository(impl: DjMixRepositoryImpl): DjMixRepository

    companion object {

        @Provides
        @Singleton
        fun provideDjMixDatabase(@ApplicationContext context: Context): DjMixDatabase =
            Room.databaseBuilder(
                context,
                DjMixDatabase::class.java,
                "dj_mix_db"
            )
                .addMigrations(DjMixDatabase.MIGRATION_1_2)
                .build()

        @Provides
        @Singleton
        fun provideBpmCacheDao(database: DjMixDatabase): BpmCacheDao =
            database.bpmCacheDao()
    }
}