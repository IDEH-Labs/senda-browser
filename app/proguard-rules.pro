# Proguard rules for GeckoView and Senda Browser
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**

# Guardian Project Tor & Control
-keep class info.guardianproject.** { *; }
-dontwarn info.guardianproject.**
-keep class net.freehaven.tor.control.** { *; }
-dontwarn net.freehaven.tor.control.**

# Hidden API Bypass for GeckoView JNI
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

