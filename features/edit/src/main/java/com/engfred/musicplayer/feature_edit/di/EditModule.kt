package com.engfred.musicplayer.feature_edit.di


import com.engfred.musicplayer.feature_edit.data.repository.EditRepositoryImpl
import com.engfred.musicplayer.feature_edit.domain.repository.EditRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EditModule {

    @Provides
    @Singleton
    fun provideEditRepository(): EditRepository {
        return EditRepositoryImpl()
    }
}