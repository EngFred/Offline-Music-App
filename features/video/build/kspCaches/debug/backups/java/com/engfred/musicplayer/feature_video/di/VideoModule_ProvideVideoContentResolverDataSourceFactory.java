package com.engfred.musicplayer.feature_video.di;

import android.content.Context;
import com.engfred.musicplayer.feature_video.data.source.local.VideoContentResolverDataSource;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class VideoModule_ProvideVideoContentResolverDataSourceFactory implements Factory<VideoContentResolverDataSource> {
  private final Provider<Context> contextProvider;

  private VideoModule_ProvideVideoContentResolverDataSourceFactory(
      Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public VideoContentResolverDataSource get() {
    return provideVideoContentResolverDataSource(contextProvider.get());
  }

  public static VideoModule_ProvideVideoContentResolverDataSourceFactory create(
      Provider<Context> contextProvider) {
    return new VideoModule_ProvideVideoContentResolverDataSourceFactory(contextProvider);
  }

  public static VideoContentResolverDataSource provideVideoContentResolverDataSource(
      Context context) {
    return Preconditions.checkNotNullFromProvides(VideoModule.INSTANCE.provideVideoContentResolverDataSource(context));
  }
}
