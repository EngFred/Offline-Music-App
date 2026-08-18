package com.engfred.musicplayer.feature_video.presentation.viewmodel;

import com.engfred.musicplayer.core.domain.repository.PlaybackController;
import com.engfred.musicplayer.core.domain.repository.VideoRepository;
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
public final class VideoLibraryViewModel_Factory implements Factory<VideoLibraryViewModel> {
  private final Provider<VideoRepository> videoRepositoryProvider;

  private final Provider<PlaybackController> playbackControllerProvider;

  private VideoLibraryViewModel_Factory(Provider<VideoRepository> videoRepositoryProvider,
      Provider<PlaybackController> playbackControllerProvider) {
    this.videoRepositoryProvider = videoRepositoryProvider;
    this.playbackControllerProvider = playbackControllerProvider;
  }

  @Override
  public VideoLibraryViewModel get() {
    return newInstance(videoRepositoryProvider.get(), playbackControllerProvider.get());
  }

  public static VideoLibraryViewModel_Factory create(
      Provider<VideoRepository> videoRepositoryProvider,
      Provider<PlaybackController> playbackControllerProvider) {
    return new VideoLibraryViewModel_Factory(videoRepositoryProvider, playbackControllerProvider);
  }

  public static VideoLibraryViewModel newInstance(VideoRepository videoRepository,
      PlaybackController playbackController) {
    return new VideoLibraryViewModel(videoRepository, playbackController);
  }
}
