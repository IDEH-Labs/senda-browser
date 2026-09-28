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
        // Inicializar el motor GeckoView con directivas de privacidad LibreWolf
        SendaGeckoEngine.initialize(this)
    }
}
