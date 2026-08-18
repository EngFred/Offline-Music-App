package com.engfred.musicplayer.feature_video.di

import android.content.Context
import com.engfred.musicplayer.core.domain.repository.VideoRepository
import com.engfred.musicplayer.feature_video.data.repository.VideoRepositoryImpl
import com.engfred.musicplayer.feature_video.data.source.local.VideoContentResolverDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VideoModule {

    @Provides
    @Singleton
    fun provideVideoContentResolverDataSource(
        @ApplicationContext context: Context
    ): VideoContentResolverDataSource {
        return VideoContentResolverDataSource(context)
    }

    @Provides
    @Singleton
    fun provideVideoRepository(
        dataSource: VideoContentResolverDataSource
    ): VideoRepository {
        return VideoRepositoryImpl(dataSource)
    }

    @Provides
    fun provideVideoPlaybackController(
        @ApplicationContext context: Context
    ): com.engfred.musicplayer.core.domain.repository.VideoPlaybackController {
        return com.engfred.musicplayer.feature_video.data.repository.VideoPlaybackControllerImpl(context)
    }
}
