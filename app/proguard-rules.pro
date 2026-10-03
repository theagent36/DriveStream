# ── DriveStream ProGuard Rules ─────────────────────────────────────────────
# Preserve stack traces for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses,Exceptions

# ── App Classes ────────────────────────────────────────────────────────────
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep class com.example.drivestream.DriveStreamApp { *; }

# ── App Data Models (Serialized via Gson) ──────────────────────────────────
-keep class com.example.drivestream.DriveFile { *; }
-keep class com.example.drivestream.AppTheme { *; }
-keep class com.example.drivestream.AccentColor { *; }
-keep class com.example.drivestream.SortState { *; }
-keep class com.example.drivestream.SortProperty { *; }
-keep class com.example.drivestream.SortDirection { *; }
-keep class com.example.drivestream.SpotifyAuthResponse { *; }
-keep class com.example.drivestream.SpotifySearchResponse { *; }
-keep class com.example.drivestream.SpotifyTracks { *; }
-keep class com.example.drivestream.SpotifyTrack { *; }
-keep class com.example.drivestream.SpotifyAlbum { *; }
-keep class com.example.drivestream.SpotifyImage { *; }
-keep class com.example.drivestream.SpotifyOEmbedResponse { *; }
-keep class com.example.drivestream.ITunesSearchResponse { *; }
-keep class com.example.drivestream.ITunesTrackResult { *; }
-keep class com.example.drivestream.SpotifyAuthManager { *; }

# Keep the entire app package - nuclear option to prevent R8 from stripping coroutine machinery
-keep class com.example.drivestream.** { *; }
-keep interface com.example.drivestream.** { *; }

# ── Google API Client (uses reflection heavily) ────────────────────────────
-keep class com.google.api.** { *; }
-keep class com.google.apis.** { *; }
-keep class com.google.http.client.** { *; }
-dontwarn com.google.api.client.**
-dontwarn com.google.api.client.extensions.android.**
-dontwarn com.google.api.client.googleapis.extensions.android.**

# ── Google Auth / Play Services ────────────────────────────────────────────
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ── Gson (Google HTTP client serialization) ────────────────────────────────
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Prevent stripping generic type info needed by Gson TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.Expose <fields>;
}
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ── AndroidX Media3 / ExoPlayer ────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep custom ExoPlayer policies to prevent stripping
-keep class com.example.drivestream.InfiniteRetryLoadErrorHandlingPolicy { *; }

# ── Android Audio Effects (LoudnessEnhancer, Visualizer) ──────────────────
# These are loaded reflectively by the framework and must not be stripped
-keep class android.media.audiofx.** { *; }

# ── AndroidX WorkManager (CacheCleanupWorker) ──────────────────────────────
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**

# ── Firebase ───────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── Retrofit / OkHttp (Spotify API) ───────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations
-keepattributes AnnotationDefault

-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn okio.**

-keep interface com.example.drivestream.SpotifyAuthService { *; }
-keep interface com.example.drivestream.SpotifySearchService { *; }
-keep interface com.example.drivestream.SpotifyOEmbedService { *; }
-keep interface com.example.drivestream.ITunesSearchService { *; }
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ── Coil (image loading) ───────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**
-keep class * implements coil.ImageLoaderFactory {
    public coil.ImageLoader newImageLoader();
}

# ── Kotlin Coroutines ──────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.sync.** { *; }
-keepclassmembers class ** {
    suspend <methods>;
}
-dontwarn kotlinx.coroutines.**

# ── Jetpack Compose ────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── AndroidX DataStore ─────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ── Palette API ────────────────────────────────────────────────────────────
-keep class androidx.palette.** { *; }

# ── Suppress noisy missing-class warnings from legacy libraries ───────────
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**
-dontwarn com.google.errorprone.**
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.instrument.**
