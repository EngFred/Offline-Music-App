package com.engfred.musicplayer.feature_playlist.di

import android.content.Context
import androidx.room.Room
import com.engfred.musicplayer.feature_playlist.data.local.dao.PlaylistDao
import com.engfred.musicplayer.feature_playlist.data.local.db.PlaylistDatabase
import com.engfred.musicplayer.feature_playlist.data.repository.PlaylistRepositoryImpl
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlaylistModule {

    @Provides
    @Singleton
    fun providePlaylistDatabase(@ApplicationContext context: Context): PlaylistDatabase {
        return Room.databaseBuilder(
            context,
            PlaylistDatabase::class.java,
            "music_player_playlist_db"
        )
            .addMigrations(PlaylistDatabase.MIGRATION_2_3)
            .build()
    }

    @Provides
    @Singleton
    fun providePlaylistDao(database: PlaylistDatabase): PlaylistDao {
        return database.playlistDao()
    }

    @Provides
    @Singleton
    fun providePlaylistRepository(repositoryImpl: PlaylistRepositoryImpl): PlaylistRepository {
        return repositoryImpl
    }
}