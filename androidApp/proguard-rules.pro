# DSH Mobile ProGuard rules (release build).
# Minification is disabled for v1; rules here are ready for when it is enabled.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.dsh.mobile.mesh.core.wire.dto.** {
    *** Companion;
}
-keepclasseswithmembers class dev.dsh.mobile.mesh.core.wire.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# sshj's optional EdDSA implementation references a JDK-only class that is not
# present on Android. The implementation is not used by the Android runtime.
-dontwarn sun.security.x509.X509Key
