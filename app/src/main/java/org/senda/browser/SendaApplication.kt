package org.senda.browser

import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.senda.browser.core.SendaGeckoEngine

class SendaApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Desbloquear APIs ocultas de ART para compatibilidad con puentes JNI de GeckoView en Android 9-15
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
        // Asegurar que GeckoRuntime solo se inicialice en el proceso principal de la UI,
        // no en los procesos secundarios de Gecko (:tab, :gpu, :crashhelper, etc.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = Application.getProcessName()
            if (processName != packageName) {
                return
            }
        }
        // Capturar y registrar excepciones no controladas para evitar cierres silenciosos
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

        // Inicializar el motor GeckoView con directivas de privacidad y preferencias
        val prefs = org.senda.browser.core.PreferencesManager(this)
        org.senda.browser.core.SendaLocaleManager.applyLocale(this, prefs.appLanguage)
        SendaGeckoEngine.initialize(this, prefs)

        instance = this
        // Modo TV: adaptar el teléfono mientras se duplica en una TV, y restaurarlo si quedó adaptado
        org.senda.browser.core.cast.SendaTvMode.init(this)
        Thread {
            // Senda ya no incluye IA (2026-10-05): ningún modelo pequeño respondía con fiabilidad en sus 8 idiomas
            // en un teléfono. Se borran los modelos descargados (hasta 3,3 GB) que ya no se pueden usar
            java.io.File(filesDir, "models_ai").deleteRecursively()
            // Copias de archivos subidos a páginas en la sesión anterior
            org.senda.browser.core.SendaWebUploads.cleanup(this)
        }.start()
        // Inicializar gestor de Tor integrado
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
