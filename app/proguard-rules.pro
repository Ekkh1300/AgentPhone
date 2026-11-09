# Keep serialized models and Room entities.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.agentphone.app.**$$serializer { *; }
-keepclassmembers class com.agentphone.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.agentphone.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.agentphone.app.data.local.entity.** { *; }
-keep class com.agentphone.app.data.remote.dto.** { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Jsoup
-keep class org.jsoup.** { *; }

# TDLib: libtdjni.so resolves these Java classes through JNI, so R8 must not rename or strip them.
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**

# Keep Hilt generated code metadata.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
