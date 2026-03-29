# ════════════════════════════════════════════════════════════════════
# MusicPlayer — app/proguard-rules.pro
# ════════════════════════════════════════════════════════════════════

# ── 1. Kotlin ────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-dontwarn kotlin.**

# ── 2. Coroutines ────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── 3. Hilt / Dagger ─────────────────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keepclasseswithmembers class * { @dagger.* <methods>; }
-dontwarn dagger.**
-dontwarn javax.inject.**

# ── 4. Room ──────────────────────────────────────────────────────────
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
# Keep the whole data.local package — covers Entity, Dao, Db, Converter, _Impl
-keep class com.engfred.musicplayer.feature_dj_mix.data.local.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }

# ── 5. WorkManager + Hilt Workers ────────────────────────────────────
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep @androidx.hilt.work.HiltWorker class * { *; }
-dontwarn androidx.work.**

# ── 6. Media3 / ExoPlayer ────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Your custom AudioProcessor implementations
-keep class com.engfred.musicplayer.feature_dj_mix.data.crossfade.RubberBandAudioProcessor { *; }
-keep class com.engfred.musicplayer.feature_dj_mix.data.crossfade.WaveformCaptureAudioProcessor { *; }
-keep class com.engfred.musicplayer.feature_dj_mix.data.crossfade.DrcSuppressingMediaCodecAdapterFactory { *; }

# ── 7. JNI — RubberBand (rubberband_processor.cpp) ───────────────────
-keepclasseswithmembernames class com.engfred.musicplayer.feature_dj_mix.data.crossfade.RubberBandAudioProcessor {
    native <methods>;
}

# ── 8. JNI — aubio BPM bridge (aubio_bridge.c) ───────────────────────
# Covers whatever Kotlin class in data.bpm declares the native methods.
-keepclasseswithmembernames class com.engfred.musicplayer.feature_dj_mix.data.bpm.** {
    native <methods>;
}

# Safety net: keep native methods in ANY class across the whole app
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── JNI — aubio BPM bridge (BpmAnalyzer.analyzeBeatsNative) ───────
-keepclasseswithmembernames class com.engfred.musicplayer.feature_dj_mix.data.bpm.BpmAnalyzer {
    native <methods>;
}
-keepclasseswithmembernames class com.engfred.musicplayer.feature_dj_mix.data.bpm.** {
    native <methods>;
}

-keep class * extends androidx.media3.common.audio.BaseAudioProcessor { *; }

-keep @dagger.assisted.AssistedInject class * { *; }
-keepclassmembers class * {
    @dagger.assisted.AssistedInject <init>(...);
}

# ── 9. FFmpeg-kit ─────────────────────────────────────────────────────
-keep class com.arthenica.** { *; }
-keep class io.github.maitrungduc1410.** { *; }
-dontwarn com.arthenica.**
-dontwarn io.github.maitrungduc1410.**

# ── 10. DataStore Preferences ────────────────────────────────────────
# You use datastore-preferences (NOT proto DataStore) — no protobuf rule needed.
-dontwarn androidx.datastore.**

# ── 11. Coil / Landscapist ───────────────────────────────────────────
-keep class coil.** { *; }
-keep class com.skydoves.landscapist.** { *; }
-dontwarn coil.**
-dontwarn com.skydoves.**

# ── 12. Reorderable ──────────────────────────────────────────────────
-keep class sh.calvin.reorderable.** { *; }
-dontwarn sh.calvin.**

# ── 13. Domain models & DJ Mix state ─────────────────────────────────
-keep class com.engfred.musicplayer.core.domain.model.** { *; }
-keep class com.engfred.musicplayer.feature_dj_mix.domain.** { *; }
-keep class com.engfred.musicplayer.feature_dj_mix.data.crossfade.CrossfadeEngineState { *; }
-keep class com.engfred.musicplayer.feature_dj_mix.data.crossfade.MixStrategy { *; }
-keep class com.engfred.musicplayer.feature_dj_mix.data.crossfade.MixDecision { *; }

# ── 14. Enums ────────────────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── 15. Parcelable / Serializable ────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── 16. Misc suppression ─────────────────────────────────────────────
-dontwarn sun.misc.**
-dontwarn java.lang.invoke.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**