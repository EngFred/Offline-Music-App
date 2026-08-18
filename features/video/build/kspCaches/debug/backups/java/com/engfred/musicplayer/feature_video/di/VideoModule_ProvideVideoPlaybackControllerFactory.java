package com.engfred.musicplayer.feature_video.di;

import android.content.Context;
import com.engfred.musicplayer.core.domain.repository.VideoPlaybackController;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
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
public final class VideoModule_ProvideVideoPlaybackControllerFactory implements Factory<VideoPlaybackController> {
  private final Provider<Context> contextProvider;

  private VideoModule_ProvideVideoPlaybackControllerFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public VideoPlaybackController get() {
    return provideVideoPlaybackController(contextProvider.get());
  }

  public static VideoModule_ProvideVideoPlaybackControllerFactory create(
      Provider<Context> contextProvider) {
    return new VideoModule_ProvideVideoPlaybackControllerFactory(contextProvider);
  }

  public static VideoPlaybackController provideVideoPlaybackController(Context context) {
    return Preconditions.checkNotNullFromProvides(VideoModule.INSTANCE.provideVideoPlaybackController(context));
  }
}
