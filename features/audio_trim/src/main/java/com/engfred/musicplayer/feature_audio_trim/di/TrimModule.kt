package com.engfred.musicplayer.feature_audio_trim.di

import com.engfred.musicplayer.feature_audio_trim.data.repository.TrimRepositoryImpl
import com.engfred.musicplayer.feature_audio_trim.domain.repository.TrimRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TrimModule {

    @Binds
    @Singleton
    abstract fun bindTrimRepository(impl: TrimRepositoryImpl): TrimRepository
}