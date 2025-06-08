# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Jetpack Compose
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.runtime.**

# Suppress Phenotype API warnings
-dontwarn com.google.android.gms.phenotype.**