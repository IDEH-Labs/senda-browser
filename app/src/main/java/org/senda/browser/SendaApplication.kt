package org.senda.browser

import android.app.Application
import org.senda.browser.core.SendaGeckoEngine

class SendaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Inicializar el motor GeckoView con directivas de privacidad LibreWolf
        SendaGeckoEngine.initialize(this)
    }
}
