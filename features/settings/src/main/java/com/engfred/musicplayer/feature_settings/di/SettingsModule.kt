package com.engfred.musicplayer.feature_settings.di

import android.content.Context
import com.engfred.musicplayer.feature_settings.data.repository.SettingsRepositoryImpl
import com.engfred.musicplayer.feature_settings.domain.usecases.GetAppSettingsUseCase
import com.engfred.musicplayer.feature_settings.domain.usecases.UpdateThemeUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.engfred.musicplayer.core.domain.repository.SettingsRepository

// Define the DataStore instance as an extension property
private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.appSettingsDataStore
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
        dataStore: DataStore<Preferences>
    ): SettingsRepository {
        return SettingsRepositoryImpl(dataStore)
    }

    @Provides
    @Singleton
    fun provideGetAppSettingsUseCase(
        settingsRepository: SettingsRepository
    ): GetAppSettingsUseCase {
        return GetAppSettingsUseCase(settingsRepository)
    }

    @Provides
    @Singleton
    fun provideUpdateThemeUseCase(
        settingsRepository: SettingsRepository
    ): UpdateThemeUseCase {
        return UpdateThemeUseCase(settingsRepository)
    }
}