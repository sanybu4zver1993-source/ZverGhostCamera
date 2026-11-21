# ZVER Camera R8 / ProGuard Rules

# CameraX, Camera2 Interop & OEM Extensions
-keep class androidx.camera.camera2.Camera2Config$DefaultProvider { *; }
-keep class androidx.camera.core.impl.** { *; }
-keep class androidx.camera.extensions.** { *; }
-keepclassmembers class * implements androidx.camera.extensions.impl.** { *; }

# ML Kit JNI & Native Registrars (bundled offline models)
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.libraries.vision.** { *; }
-keepclassmembers class * {
    @com.google.android.apps.common.proguard.UsedByNative *;
}
-dontwarn com.google.mlkit.**

# Media3 Common (SonicAudioProcessor WSOLA)
-keep class androidx.media3.common.audio.SonicAudioProcessor { *; }
-keep class androidx.media3.common.audio.Sonic { *; }
-dontwarn androidx.media3.**

# RootBeer JNI
-keep class com.scottyab.rootbeer.RootBeerNative { *; }

# OkHttp 4.10
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coroutines & Serialization / Reflection safety
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
