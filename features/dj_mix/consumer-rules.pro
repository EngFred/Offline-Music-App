# Consumers of :features:dj_mix inherit these rules automatically.

# JNI bridges — must survive in any consumer APK
-keepclasseswithmembernames class com.engfred.musicplayer.feature_dj_mix.data.bpm.** {
    native <methods>;
}

# Room layer
-keep class com.engfred.musicplayer.feature_dj_mix.data.local.** { *; }

# Public state types used by presentation layer
-keep class com.engfred.musicplayer.feature_dj_mix.data.crossfade.CrossfadeEngineState { *; }
-keep class com.engfred.musicplayer.feature_dj_mix.data.crossfade.MixStrategy { *; }
-keep class com.engfred.musicplayer.feature_dj_mix.data.crossfade.MixDecision { *; }
-keep class com.engfred.musicplayer.feature_dj_mix.domain.repository.BpmInfo { *; }