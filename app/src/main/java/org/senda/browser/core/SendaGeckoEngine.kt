package org.senda.browser.core

import android.content.Context
import android.net.Uri
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

object SendaGeckoEngine {

    private var runtime: GeckoRuntime? = null
    val installedExtensions = mutableListOf<WebExtension>()

    fun initialize(context: Context) {
        if (runtime != null) return

        // Configuración estricta de privacidad inspirada en LibreWolf
        val settings = GeckoRuntimeSettings.Builder()
            // 1. Bloqueo estricto de rastreo y aislamiento de cookies
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
                    .strictSocialTrackingProtection(true)
                    .build()
            )
            // 2. Habilitar about:config para que el usuario tenga control total
            .aboutConfigEnabled(true)
            // 3. Desactivar logs innecesarios en consola
            .consoleOutput(false)
            // 4. Modo de depuración web solo cuando el usuario lo pida
            .remoteDebuggingEnabled(false)
            .build()

        runtime = GeckoRuntime.create(context.applicationContext, settings)
    }

    fun getRuntime(): GeckoRuntime {
        return checkNotNull(runtime) {
            "SendaGeckoEngine no fue inicializado antes de solicitar el runtime"
        }
    }

    /**
     * Crea una nueva sesión de Gecko (Pestaña) con configuraciones de seguridad
     */
    fun createSession(isPrivate: Boolean = true): GeckoSession {
        val sessionSettings = org.mozilla.geckoview.GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .userAgentMode(org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .build()

        val session = GeckoSession(sessionSettings)
        session.open(getRuntime())
        return session
    }

    /**
     * Permite instalar libremente cualquier extensión .xpi desde un archivo local o URI
     */
    fun installExtension(uri: Uri, onSuccess: (WebExtension) -> Unit, onError: (Throwable) -> Unit) {
        val controller = getRuntime().webExtensionController
        controller.install(uri.toString())
            .accept(
                { extension: WebExtension? ->
                    if (extension != null) {
                        installedExtensions.add(extension)
                        onSuccess(extension)
                    }
                },
                { throwable: Throwable? ->
                    if (throwable != null) {
                        onError(throwable)
                    }
                }
            )
    }
}
