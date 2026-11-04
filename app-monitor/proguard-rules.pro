# Cloud Monitor — ProGuard/R8 rules.
#
# R8 is DISABLED for the release build (isMinifyEnabled = false): a background service that
# reads notifications and usage statistics is a reliability risk when obfuscated, and the
# APK is installed on a system that only ever installs our own build.
#
# These rules exist so that the build also works if someone turns R8 on later.

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

# The WorkManager worker is instantiated by the framework.
-keep class com.cloudmonitor.service.WatchdogWorker { *; }

# The system instantiates the services below; their names must survive.
-keep class com.cloudmonitor.service.MonitorForegroundService { *; }
-keep class com.cloudmonitor.monitor.MonitorNotificationListener { *; }
-keep class com.cloudmonitor.monitor.AnrWatcherAccessibilityService { *; }
-keep class com.cloudmonitor.service.BootReceiver { *; }
-keep class com.cloudmonitor.service.PackageReplacedReceiver { *; }
