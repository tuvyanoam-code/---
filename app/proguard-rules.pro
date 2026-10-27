# The Anthropic SDK, OkHttp, kotlinx.serialization and Play services ship their own keep rules.

# Optional integrations referenced by the SDK's dependencies but not used on Android.
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.joda.time.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Our persisted JSON models are serialized with kotlinx.serialization (rules bundled with the library).
