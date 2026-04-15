package com.engfred.musicplayer.di

import com.engfred.musicplayer.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AppVersionModule {

    @Provides
    @Named("versionName")
    fun provideVersionName(): String = BuildConfig.VERSION_NAME
}