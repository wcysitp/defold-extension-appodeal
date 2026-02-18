# Appodeal core
-keep class com.defold.appodeal.AppodealBridge { *; }
-keep class com.appodeal.ads.** { *; }
-keep class com.explorestack.iab.** { *; }
-dontwarn com.appodeal.ads.**
-dontwarn com.explorestack.iab.**

# Appodeal consent
-keep class com.appodeal.consent.** { *; }
-dontwarn com.appodeal.consent.**

# Appodeal mediation adapters
-keep class com.appodeal.ads.adapters.** { *; }
-keep class com.appodeal.ads.unified.** { *; }
-dontwarn com.appodeal.ads.adapters.**
-dontwarn com.appodeal.ads.unified.**

# BidMachine
-keep class io.bidmachine.** { *; }
-dontwarn io.bidmachine.**

# Third-party ad SDKs (minimal keep rules)
-dontwarn com.applovin.**
-dontwarn com.unity3d.ads.**
-dontwarn com.vungle.**
-dontwarn com.yandex.mobile.ads.**
