# Tink references optional annotations / protobuf classes that are not needed at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.** { *; }
-keep class com.wristradar.data.db.** { *; }
-dontwarn org.bouncycastle.**
