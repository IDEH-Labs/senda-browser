# Proguard rules for GeckoView and Senda Browser
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**

# Guardian Project Tor & Control. TorService lives in org.torproject.jni and its native code reads fields by name
# (torConfiguration): without this rule R8 renamed them and turning on Tor crashed the signed build (2026-10-07)
-keep class org.torproject.jni.** { *; }
-dontwarn org.torproject.jni.**
-keep class info.guardianproject.** { *; }
-dontwarn info.guardianproject.**
-keep class net.freehaven.tor.control.** { *; }
-dontwarn net.freehaven.tor.control.**

# Hidden API Bypass for GeckoView JNI
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**


# GeckoView reads geckoview-config.yaml (Senda's startup prefs) with SnakeYAML and loads it into DebugConfig
# by reflection. Without these rules R8 repackages SnakeYAML, Package is null and the signed build crashed at
# startup (ExceptionInInitializerError in DebugConfig.fromFile, 2026-10-07)
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
-keep class org.mozilla.gecko.util.DebugConfig { *; }
-keep class org.mozilla.gecko.util.DebugConfig$* { *; }
