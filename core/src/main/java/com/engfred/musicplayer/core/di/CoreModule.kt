package com.engfred.musicplayer.core.di

import android.content.Context
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.usecases.PermissionHandlerUseCase
import com.engfred.musicplayer.core.mapper.AudioFileMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideSharedAudioDataSource(): SharedAudioDataSource {
        return SharedAudioDataSource()
    }

    @Provides
    @Singleton
    fun provideAudioFileMapper(): AudioFileMapper {
        return AudioFileMapper()
    }

    @Provides
    @Singleton
    fun providePermissionHandlerUseCase(
        @ApplicationContext context: Context
    ): PermissionHandlerUseCase {
        return PermissionHandlerUseCase(context)
    }

    @Provides
    @Singleton
    fun provideContentResolver(
        @ApplicationContext context: Context
    ): android.content.ContentResolver {
        return context.contentResolver
    }

    @Provides
    @Singleton
    fun provideLocalMediaHttpServer(
        contentResolver: android.content.ContentResolver
    ): com.engfred.musicplayer.core.data.server.LocalMediaHttpServer {
        return com.engfred.musicplayer.core.data.server.LocalMediaHttpServer(contentResolver)
    }
}