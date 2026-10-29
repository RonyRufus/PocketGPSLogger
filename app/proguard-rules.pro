# ---------------------------------------------------------------------------
# Vosk (offline recognizer) + its JNA native bridge.
# The recognizer is reached through JNI/JNA, so keep the whole surface.
# ---------------------------------------------------------------------------
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn org.vosk.**

# ---------------------------------------------------------------------------
# Room: entities, DAOs and generated implementations are referenced reflectively.
# ---------------------------------------------------------------------------
-keep class com.pocketgps.logger.data.** { *; }
-keepclassmembers class com.pocketgps.logger.data.** {
    <init>(...);
    *;
}

# ---------------------------------------------------------------------------
# Android components referenced from the manifest.
# ---------------------------------------------------------------------------
-keep class com.pocketgps.logger.App { *; }
-keep class com.pocketgps.logger.service.HandsFreeService { *; }
-keep class com.pocketgps.logger.ui.main.MainActivity { *; }

# ---------------------------------------------------------------------------
# TextToSpeech is a platform class; no keep needed, but silence optional
# javax.speech references that some TTS engine stubs mention.
# ---------------------------------------------------------------------------
-dontwarn javax.speech.**
-dontwarn android.speech.**
