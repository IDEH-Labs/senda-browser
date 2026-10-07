# Proguard rules for GeckoView and Senda Browser
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**

# Guardian Project Tor & Control. TorService vive en org.torproject.jni y su código nativo lee campos por nombre
# (torConfiguration): sin esta regla R8 los renombraba y activar Tor cerraba la versión firmada (2026-10-07)
-keep class org.torproject.jni.** { *; }
-dontwarn org.torproject.jni.**
-keep class info.guardianproject.** { *; }
-dontwarn info.guardianproject.**
-keep class net.freehaven.tor.control.** { *; }
-dontwarn net.freehaven.tor.control.**

# Hidden API Bypass for GeckoView JNI
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**


# GeckoView lee geckoview-config.yaml (prefs de arranque de Senda) con SnakeYAML y lo vuelca por reflexión en
# DebugConfig. Sin estas reglas R8 reempaqueta SnakeYAML, Package queda null y la versión firmada se cerraba al
# arrancar (ExceptionInInitializerError en DebugConfig.fromFile, 2026-10-07)
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
-keep class org.mozilla.gecko.util.DebugConfig { *; }
-keep class org.mozilla.gecko.util.DebugConfig$* { *; }
