# Firebase
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.firebase.firestore.** { *; }
-keep class com.google.firebase.storage.** { *; }
-keep class com.google.firebase.analytics.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.firebase.**

# Jetpack Compose
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }
-keepattributes *Annotation*
-keepattributes InnerClasses
-dontwarn androidx.compose.**

# Google Play Services (Phenotype API)
-keep class com.google.android.gms.phenotype.** { *; }
-dontwarn com.google.android.gms.phenotype.**

# Prevent R8 from removing unused classes in Firebase data models
-keep class com.indianlegend.videoquest.Task { *; }
-keep class com.indianlegend.videoquest.User { *; }
-keep class com.indianlegend.videoquest.VideoMetadata { *; }
-keepattributes *Annotation*
-keepattributes Signature

# ExoPlayer (used in VideoPlaybackScreen)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# CameraX (used in VideoRecordScreen)
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**