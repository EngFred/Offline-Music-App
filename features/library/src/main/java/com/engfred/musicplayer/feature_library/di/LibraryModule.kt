package com.engfred.musicplayer.feature_library.di
import android.content.Context
import com.engfred.musicplayer.feature_library.data.repository.LibraryRepositoryImpl
import com.engfred.musicplayer.feature_library.data.source.local.ContentResolverDataSource
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {

    @Provides
    @Singleton
    fun provideContentResolverDataSource(@ApplicationContext context: Context): ContentResolverDataSource {
        return ContentResolverDataSource(context)
    }

    @Provides
    @Singleton
    fun provideAudioFileRepository(
        dataSource: ContentResolverDataSource,
        settingsRepository: SettingsRepository
    ): LibraryRepository {
        return LibraryRepositoryImpl(dataSource, settingsRepository)
    }
}