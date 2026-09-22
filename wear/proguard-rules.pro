# kotlinx.serialization keeps generated serializers referenced only reflectively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.wristcontrol.wear.** {
    *** Companion;
}
-keepclasseswithmembers class dev.wristcontrol.wear.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp ships optional Conscrypt/BouncyCastle hooks that are absent at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# TileService and the notification/tile trampoline activities are entry points
# reached only by name from the system.
-keep class dev.wristcontrol.wear.tile.** { *; }
