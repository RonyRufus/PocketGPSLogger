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
-keep class com.pocketgps.logger.service.TapLogService { *; }
-keep class com.pocketgps.logger.ui.main.MainActivity { *; }

-dontwarn android.speech.**
