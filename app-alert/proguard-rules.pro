# Cloud Alert — ProGuard/R8 rules.
#
# R8 is DISABLED for the release build (isMinifyEnabled = false). These rules exist so that
# the build also works if someone turns R8 on later.

# kotlinx.serialization keeps the generated serializers via the companion object.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the error codes and DTOs of the shared module readable by the backend contract.
-keep class com.cloudmonitor.shared.** { *; }

# Hilt / Dagger generated classes.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Firebase instantiates the messaging service by name.
-keep class com.cloudalert.fcm.AlertFirebaseMessagingService { *; }
