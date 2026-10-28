-keepclasseswithmembernames class com.bedrockproxy.native.** {
    native <methods>;
}

# Mantener clases nativas usadas por JNI
-keep class com.bedrockproxy.native.RakNetPeer { *; }
-keep class com.bedrockproxy.native.Packet { *; }
-keep class com.bedrockproxy.native.SystemAddress { *; }

# Mantener constructores usados por reflexión / JNI
-keepclassmembers class com.bedrockproxy.native.** {
    <init>(...);
}

# DataStore / Kotlin
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**
