package com.engfred.musicplayer.feature_video.di;

import com.engfred.musicplayer.core.domain.repository.VideoRepository;
import com.engfred.musicplayer.feature_video.data.source.local.VideoContentResolverDataSource;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
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
public final class VideoModule_ProvideVideoRepositoryFactory implements Factory<VideoRepository> {
  private final Provider<VideoContentResolverDataSource> dataSourceProvider;

  private VideoModule_ProvideVideoRepositoryFactory(
      Provider<VideoContentResolverDataSource> dataSourceProvider) {
    this.dataSourceProvider = dataSourceProvider;
  }

  @Override
  public VideoRepository get() {
    return provideVideoRepository(dataSourceProvider.get());
  }

  public static VideoModule_ProvideVideoRepositoryFactory create(
      Provider<VideoContentResolverDataSource> dataSourceProvider) {
    return new VideoModule_ProvideVideoRepositoryFactory(dataSourceProvider);
  }

  public static VideoRepository provideVideoRepository(VideoContentResolverDataSource dataSource) {
    return Preconditions.checkNotNullFromProvides(VideoModule.INSTANCE.provideVideoRepository(dataSource));
  }
}
