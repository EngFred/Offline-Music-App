package com.engfred.musicplayer.feature_video.presentation.viewmodel;

import androidx.lifecycle.SavedStateHandle;
import com.engfred.musicplayer.core.domain.ActivePlayerRegistry;
import com.engfred.musicplayer.core.domain.cast.VideoCastManager;
import com.engfred.musicplayer.core.domain.repository.PlaybackController;
import com.engfred.musicplayer.core.domain.repository.VideoPlaybackController;
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
public final class VideoPlayerViewModel_Factory implements Factory<VideoPlayerViewModel> {
  private final Provider<VideoRepository> videoRepositoryProvider;

  private final Provider<VideoPlaybackController> videoPlaybackControllerProvider;

  private final Provider<PlaybackController> musicPlaybackControllerProvider;

  private final Provider<VideoCastManager> videoCastManagerProvider;

  private final Provider<ActivePlayerRegistry> activePlayerRegistryProvider;

  private final Provider<SavedStateHandle> savedStateHandleProvider;

  private VideoPlayerViewModel_Factory(Provider<VideoRepository> videoRepositoryProvider,
      Provider<VideoPlaybackController> videoPlaybackControllerProvider,
      Provider<PlaybackController> musicPlaybackControllerProvider,
      Provider<VideoCastManager> videoCastManagerProvider,
      Provider<ActivePlayerRegistry> activePlayerRegistryProvider,
      Provider<SavedStateHandle> savedStateHandleProvider) {
    this.videoRepositoryProvider = videoRepositoryProvider;
    this.videoPlaybackControllerProvider = videoPlaybackControllerProvider;
    this.musicPlaybackControllerProvider = musicPlaybackControllerProvider;
    this.videoCastManagerProvider = videoCastManagerProvider;
    this.activePlayerRegistryProvider = activePlayerRegistryProvider;
    this.savedStateHandleProvider = savedStateHandleProvider;
  }

  @Override
  public VideoPlayerViewModel get() {
    return newInstance(videoRepositoryProvider.get(), videoPlaybackControllerProvider.get(), musicPlaybackControllerProvider.get(), videoCastManagerProvider.get(), activePlayerRegistryProvider.get(), savedStateHandleProvider.get());
  }

  public static VideoPlayerViewModel_Factory create(
      Provider<VideoRepository> videoRepositoryProvider,
      Provider<VideoPlaybackController> videoPlaybackControllerProvider,
      Provider<PlaybackController> musicPlaybackControllerProvider,
      Provider<VideoCastManager> videoCastManagerProvider,
      Provider<ActivePlayerRegistry> activePlayerRegistryProvider,
      Provider<SavedStateHandle> savedStateHandleProvider) {
    return new VideoPlayerViewModel_Factory(videoRepositoryProvider, videoPlaybackControllerProvider, musicPlaybackControllerProvider, videoCastManagerProvider, activePlayerRegistryProvider, savedStateHandleProvider);
  }

  public static VideoPlayerViewModel newInstance(VideoRepository videoRepository,
      VideoPlaybackController videoPlaybackController, PlaybackController musicPlaybackController,
      VideoCastManager videoCastManager, ActivePlayerRegistry activePlayerRegistry,
      SavedStateHandle savedStateHandle) {
    return new VideoPlayerViewModel(videoRepository, videoPlaybackController, musicPlaybackController, videoCastManager, activePlayerRegistry, savedStateHandle);
  }
}
