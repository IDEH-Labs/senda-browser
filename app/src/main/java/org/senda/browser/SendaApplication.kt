package org.senda.browser

import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.senda.browser.core.SendaGeckoEngine

class SendaApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Unlock ART hidden APIs for compatibility with GeckoView JNI bridges on Android 9-15
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Make sure GeckoRuntime is only initialized in the main UI process,
        // not in Gecko's child processes (:tab, :gpu, :crashhelper, etc.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = Application.getProcessName()
            if (processName != packageName) {
                return
            }
        }
        // Catch and log uncaught exceptions to avoid silent crashes
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("SendaCrash", "FATAL EXCEPTION in thread ${thread.name}", throwable)
            try {
                val crashFile = java.io.File(filesDir, "crash_log.txt")
                val sw = java.io.StringWriter()
                val pw = java.io.PrintWriter(sw)
                throwable.printStackTrace(pw)
                crashFile.appendText("Time: ${java.util.Date()}\nThread: ${thread.name}\n$sw\n---\n")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Initialize the GeckoView engine with privacy directives and preferences
        val prefs = org.senda.browser.core.PreferencesManager(this)
        org.senda.browser.core.SendaLocaleManager.applyLocale(this, prefs.appLanguage)
        SendaGeckoEngine.initialize(this, prefs)

        instance = this
        // TV mode: adapt the phone while mirroring to a TV, and restore it if it was left adapted
        org.senda.browser.core.cast.SendaTvMode.init(this)
        Thread {
            // Senda no longer includes on-device AI (2026-10-05): no small model answered reliably in its 8 languages
            // on a phone. Downloaded models (up to 3.3 GB) that can no longer be used are deleted
            java.io.File(filesDir, "models_ai").deleteRecursively()
            // Copies of files uploaded to pages in the previous session
            org.senda.browser.core.SendaWebUploads.cleanup(this)
        }.start()
        // Initialize the built-in Tor manager
        org.senda.browser.core.SendaTorManager.init(this)
        if (prefs.proxyMode == "TOR_ORBOT") {
            org.senda.browser.core.SendaTorManager.start(this) {
                SendaGeckoEngine.applyProxy(prefs)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                android.util.Log.w("SendaMemory", "Presión crítica de memoria en Android (level=$level). Liberando memoria de trabajo de IA para proteger pestañas de navegación.")
                System.gc()
            }
        }
    }

    companion object {
        lateinit var instance: SendaApplication
            private set
    }
}
