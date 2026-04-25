# OkHttp + Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# IME service and Application class — referenced from AndroidManifest only
-keep class com.tyraen.voicekeyboard.feature.ime.DictationInputMethod { *; }
-keep class com.tyraen.voicekeyboard.app.DictationApp { *; }

# Activities referenced from manifest
-keep class com.tyraen.voicekeyboard.feature.setup.SetupActivity { *; }
-keep class com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingActivity { *; }
