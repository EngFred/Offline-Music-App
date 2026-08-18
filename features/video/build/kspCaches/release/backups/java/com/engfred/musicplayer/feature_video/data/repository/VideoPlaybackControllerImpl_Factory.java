package com.engfred.musicplayer.feature_video.data.repository;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class VideoPlaybackControllerImpl_Factory implements Factory<VideoPlaybackControllerImpl> {
  private final Provider<Context> contextProvider;

  private VideoPlaybackControllerImpl_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public VideoPlaybackControllerImpl get() {
    return newInstance(contextProvider.get());
  }

  public static VideoPlaybackControllerImpl_Factory create(Provider<Context> contextProvider) {
    return new VideoPlaybackControllerImpl_Factory(contextProvider);
  }

  public static VideoPlaybackControllerImpl newInstance(Context context) {
    return new VideoPlaybackControllerImpl(context);
  }
}
