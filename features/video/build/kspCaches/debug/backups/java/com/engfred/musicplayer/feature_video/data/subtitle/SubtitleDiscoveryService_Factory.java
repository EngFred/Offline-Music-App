package com.engfred.musicplayer.feature_video.data.subtitle;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class SubtitleDiscoveryService_Factory implements Factory<SubtitleDiscoveryService> {
  private final Provider<Context> contextProvider;

  private SubtitleDiscoveryService_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public SubtitleDiscoveryService get() {
    return newInstance(contextProvider.get());
  }

  public static SubtitleDiscoveryService_Factory create(Provider<Context> contextProvider) {
    return new SubtitleDiscoveryService_Factory(contextProvider);
  }

  public static SubtitleDiscoveryService newInstance(Context context) {
    return new SubtitleDiscoveryService(context);
  }
}
