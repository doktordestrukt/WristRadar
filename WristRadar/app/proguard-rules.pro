# Tink references optional annotations / protobuf classes that are not needed at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
# Only Tink's low-level primitives are used (X25519, Ed25519, HKDF); keep just those.
-keep class com.google.crypto.tink.subtle.** { *; }
-keep class com.wristradar.data.db.** { *; }
-dontwarn org.bouncycastle.**
