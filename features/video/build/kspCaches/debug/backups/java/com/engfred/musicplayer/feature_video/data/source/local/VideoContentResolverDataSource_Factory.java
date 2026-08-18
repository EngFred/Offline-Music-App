package com.engfred.musicplayer.feature_video.data.source.local;

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
public final class VideoContentResolverDataSource_Factory implements Factory<VideoContentResolverDataSource> {
  private final Provider<Context> contextProvider;

  private VideoContentResolverDataSource_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public VideoContentResolverDataSource get() {
    return newInstance(contextProvider.get());
  }

  public static VideoContentResolverDataSource_Factory create(Provider<Context> contextProvider) {
    return new VideoContentResolverDataSource_Factory(contextProvider);
  }

  public static VideoContentResolverDataSource newInstance(Context context) {
    return new VideoContentResolverDataSource(context);
  }
}
