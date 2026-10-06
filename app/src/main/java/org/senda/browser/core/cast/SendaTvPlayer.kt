package org.senda.browser.core.cast

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.senda.browser.ui.model.BrowserTab

/**
 * Video en la TV como pantalla secundaria: mientras el teléfono se duplica en una TV (Miracast), el video que
 * suena en Senda pasa a la TV a pantalla completa, en 16:9 y a la resolución de la TV, desde el mismo segundo.
 * El teléfono no cambia (sigue en vertical y se puede seguir navegando): Android muestra en la TV lo que Senda
 * dibuja para ella ([org.senda.browser.ui.components.TvPresentationHost]) en vez del espejo del teléfono.
 *
 * YouTube no entrega un archivo: se usa su reproductor oficial (youtube-nocookie.com) en una sesión propia, sin
 * inyectar nada en las páginas. Los demás videos (MP4, HLS…) se reproducen con el reproductor de Android a partir
 * del archivo que detectó [SendaMediaCatalog]. Al terminar, el video sigue en la pestaña donde quedó.
 */
object SendaTvPlayer {

    private const val TAG = "SendaTvPlayer"

    /**
     * YouTube exige que quien muestra su reproductor se identifique con un Referer; para apps, con
     * https://<paquete>. Sin él responde «Error 153». Es la identificación real de Senda, no una suplantación.
     */
    const val REFERRER = "https://org.senda.browser"

    sealed class Playback {
        abstract val tab: BrowserTab
        abstract val startSeconds: Int
        abstract val tabDurationSeconds: Double
        abstract val key: String

        data class YouTube(
            override val tab: BrowserTab,
            val videoId: String,
            override val startSeconds: Int,
            val playlistId: String?,
            override val tabDurationSeconds: Double
        ) : Playback() {
            override val key: String get() = videoId
            val embedUrl: String
                get() = buildString {
                    append("https://www.youtube-nocookie.com/embed/").append(Uri.encode(videoId))
                    append("?autoplay=1&playsinline=1&rel=0&start=").append(startSeconds.coerceAtLeast(0))
                    playlistId?.let { append("&list=").append(Uri.encode(it)) }
                }
        }

        data class File(
            override val tab: BrowserTab,
            val media: SendaMediaCatalog.Media,
            override val startSeconds: Int,
            override val tabDurationSeconds: Double
        ) : Playback() {
            override val key: String get() = media.url
        }
    }

    /** Lo que el teléfono puede pedirle al reproductor de la TV (lo registra el reproductor que se muestra). */
    interface Controls {
        fun play()
        fun pause()
    }

    var playback by mutableStateOf<Playback?>(null)
        private set
    var paused by mutableStateOf(false)
    var controls: Controls? = null

    // Video que el usuario devolvió al teléfono o que la TV no pudo mostrar: no volver a pasarlo solo
    private var skippedKey: String? = null
    // Dónde iba en la TV un video que se retiró al salir de Senda: al volver sigue desde ahí
    private val resumeAt = HashMap<String, Int>()
    private var starting = false

    /**
     * Duplicando en una TV, el video de la página pasa solo a la TV en cuanto existe (al abrir un video de YouTube o
     * cuando la página carga su archivo), sin botones: para eso se conectó la TV. No espera a que suene en el
     * teléfono porque Gecko no siempre lo informa (MediaSession). No vuelve a pasar un video que el usuario
     * devolvió al teléfono.
     */
    fun autoStart(tab: BrowserTab?, enabled: Boolean) {
        if (!SendaTvMode.tvConnected) {
            skippedKey = null
            return
        }
        // Las pestañas privadas no pasan solas a la TV: Senda también las oculta al duplicar la pantalla
        if (!enabled || tab == null || tab.isPrivate) return
        val key = SendaYouTube.youTubeVideoId(tab.url) ?: SendaMediaCatalog.bestFor(tab.url)?.url ?: return
        if (key == skippedKey || key == playback?.key) return
        // Otro video mientras la TV muestra uno: el nuevo lo reemplaza
        if (playback != null) {
            requestReturn?.invoke()
            if (playback != null) return
        }
        start(tab)
    }

    /** Muestra en la TV el video de [tab] desde donde va (lo pidió el usuario o empezó a sonar). */
    fun start(tab: BrowserTab) {
        if (!SendaTvMode.tvConnected || playback != null || starting) return
        val videoId = SendaYouTube.youTubeVideoId(tab.url)
        val media = if (videoId == null) SendaMediaCatalog.bestFor(tab.url) else null
        val key = videoId ?: media?.url ?: return
        starting = true
        // Pausar y tomar la posición exacta en la que quedó: la TV sigue justo desde ahí
        tab.pauseMediaAndGetPosition { start ->
            starting = false
            if (!SendaTvMode.tvConnected) {
                tab.resumeMedia()
                return@pauseMediaAndGetPosition
            }
            paused = false
            val from = resumeAt.remove(key) ?: start
            playback = if (videoId != null) {
                val playlist = try { Uri.parse(tab.url).getQueryParameter("list") } catch (_: Exception) { null }
                Playback.YouTube(tab, videoId, from, playlist?.takeIf { it.isNotBlank() }, tab.mediaDurationSeconds)
            } else {
                Playback.File(tab, media!!, from, tab.mediaDurationSeconds)
            }
            Log.i(TAG, "Video a la TV desde ${from}s")
        }
    }

    /**
     * Cierra el video de la TV y lo devuelve a la pestaña. [positionSeconds] y [durationSeconds] son los del
     * reproductor de la TV (null si nunca llegó a reproducir). [userExit]: el usuario lo pidió; no se vuelve a pasar.
     * [resumeOnPhone]: false al salir de Senda (queda en pausa).
     */
    fun finish(positionSeconds: Double?, durationSeconds: Double?, userExit: Boolean, resumeOnPhone: Boolean = true) {
        val current = playback ?: return
        playback = null
        controls = null
        paused = false
        if (userExit || positionSeconds == null) skippedKey = current.key
        val tab = current.tab
        if (positionSeconds == null) {
            Log.w(TAG, "La TV no pudo reproducir ${current.key}: vuelve a la pestaña")
            tab.resumeMedia()
            return
        }
        // Con lista de reproducción la TV pudo pasar a otro video: solo se salta si sigue siendo el mismo
        val sameVideo = durationSeconds != null && current.tabDurationSeconds > 0.0 &&
            kotlin.math.abs(durationSeconds - current.tabDurationSeconds) < 1.5
        if (sameVideo) tab.seekMedia(positionSeconds)
        // Al salir de Senda el video no debe sonar en el teléfono mientras se usa otra app
        if (resumeOnPhone) tab.resumeMedia()
        Log.i(TAG, "Video de vuelta al teléfono en ${positionSeconds.toInt()}s (mismo video: $sameVideo)")
    }

    /** Pide al reproductor de la TV que devuelva el video al teléfono (con su posición). */
    var requestReturn: (() -> Unit)? = null

    /**
     * Senda pasó a segundo plano: la TV vuelve a mostrar el teléfono (otra app, p. ej. Telegram). Se recuerda dónde
     * iba el video para seguir desde ahí al volver a Senda.
     */
    var requestLeave: (() -> Unit)? = null

    internal fun rememberResume(key: String, seconds: Double?) {
        if (seconds != null && seconds > 1.0) resumeAt[key] = seconds.toInt()
    }
}
