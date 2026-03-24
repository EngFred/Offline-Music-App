# Existing rules (keep these)...
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn java.beans.**
-dontwarn org.jaudiotagger.**

# Keep JAudioTagger classes to prevent obfuscation/optimization breaking metadata ops
-keep class org.jaudiotagger.** { *; }

# Optional: If trimming still fails post-build (e.g., Transformer export errors), add this for Media3
 -keep class androidx.media3.transformer.** { *; }
 -dontwarn androidx.media3.transformer.**