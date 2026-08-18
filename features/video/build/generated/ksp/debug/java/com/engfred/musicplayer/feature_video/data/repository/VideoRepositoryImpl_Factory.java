package com.engfred.musicplayer.feature_video.data.repository;

import com.engfred.musicplayer.feature_video.data.source.local.VideoContentResolverDataSource;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
@QualifierMetadata
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
public final class VideoRepositoryImpl_Factory implements Factory<VideoRepositoryImpl> {
  private final Provider<VideoContentResolverDataSource> dataSourceProvider;

  private VideoRepositoryImpl_Factory(Provider<VideoContentResolverDataSource> dataSourceProvider) {
    this.dataSourceProvider = dataSourceProvider;
  }

  @Override
  public VideoRepositoryImpl get() {
    return newInstance(dataSourceProvider.get());
  }

  public static VideoRepositoryImpl_Factory create(
      Provider<VideoContentResolverDataSource> dataSourceProvider) {
    return new VideoRepositoryImpl_Factory(dataSourceProvider);
  }

  public static VideoRepositoryImpl newInstance(VideoContentResolverDataSource dataSource) {
    return new VideoRepositoryImpl(dataSource);
  }
}
