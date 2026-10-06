package org.senda.browser.core.cast

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * TV conectada por duplicación de pantalla (Miracast, «Enviar pantalla» o HDMI). Android la presenta como una
 * pantalla secundaria: lo que Senda dibuja en ella ([SendaTvPlayer]) se ve en la TV en su formato (16:9, a su
 * resolución) mientras el teléfono sigue exactamente igual.
 *
 * Antes Senda adaptaba el propio teléfono (horizontal, 16:9, otra resolución y escala) para que el espejo llenara
 * la TV; el usuario no quería que la pantalla del teléfono cambiara al transmitir (2026-10-06).
 */
object SendaTvMode {

    private lateinit var appContext: Context

    /** true mientras la pantalla del teléfono se duplica en una TV. */
    var tvConnected by mutableStateOf(false)
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = evaluate()
        override fun onDisplayRemoved(displayId: Int) = evaluate()
        override fun onDisplayChanged(displayId: Int) {}
    }

    /** Se llama una vez al arrancar el proceso principal. */
    fun init(context: Context) {
        if (::appContext.isInitialized) {
            evaluate()
            return
        }
        appContext = context.applicationContext
        appContext.getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        evaluate()
    }

    /** Nombre de la TV conectada, sin el sufijo de Miracast («[R1]»). */
    var tvName by mutableStateOf<String?>(null)
        private set

    fun evaluate() {
        if (!::appContext.isInitialized) return
        val tv = tvDisplay()
        tvConnected = tv != null
        tvName = tv?.name?.replace(Regex("\\[R\\d+]$"), "")?.trim()
    }

    /**
     * Desconecta la TV sin pasar por los ajustes de Android. Conectar y desconectar Miracast es de las apps del
     * sistema (CONFIGURE_WIFI_DISPLAY); la vía pública es volver a la salida de video del propio teléfono con
     * MediaRouter. Si el teléfono no lo permite, se abre el panel «Enviar pantalla», donde basta tocar «Desconectar».
     */
    fun disconnect(context: Context) {
        SendaTvPlayer.requestReturn?.invoke()
        try {
            val router = context.getSystemService(android.media.MediaRouter::class.java)
            router?.selectRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_VIDEO, router.defaultRoute)
        } catch (e: Exception) {
            android.util.Log.w("SendaTvMode", "MediaRouter no pudo desconectar: ${e.message}")
        }
        Handler(Looper.getMainLooper()).postDelayed({
            evaluate()
            if (tvConnected) org.senda.browser.ui.components.CastHelper.openSystemCast(context)
        }, 2500)
    }

    /** Pantalla de la TV en la que Senda puede mostrar contenido propio (null si no hay TV conectada). */
    fun tvDisplay(): Display? =
        if (!::appContext.isInitialized) null
        else appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
}
