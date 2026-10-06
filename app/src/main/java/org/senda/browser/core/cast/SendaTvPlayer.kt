package org.senda.browser.core.cast

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.senda.browser.ui.model.BrowserTab

/**
 * Reproductor de TV: mientras la pantalla se duplica en una TV, el video de YouTube que suena en Senda pasa
 * solo a su reproductor a pantalla completa, desde el mismo segundo. En la TV se ve como en la app de
 * YouTube (sin cabecera, sin barras, 16:9) y sin pulsar nada.
 *
 * No inyecta código en las páginas: carga el reproductor oficial de YouTube (youtube-nocookie.com) en una
 * sesión propia. Al cortar la transmisión o pulsar Atrás, el video sigue en la pestaña en el punto alcanzado.
 */
object SendaTvPlayer {

    private const val TAG = "SendaTvPlayer"

    /**
     * YouTube exige que quien muestra su reproductor se identifique con un Referer; para apps, con
     * https://<paquete>. Sin él responde «Error 153». Es la identificación real de Senda, no una suplantación.
     */
    const val REFERRER = "https://org.senda.browser"

    data class Playback(
        val tab: BrowserTab,
        val videoId: String,
        val startSeconds: Int,
        val playlistId: String?,
        val tabDurationSeconds: Double
    ) {
        val embedUrl: String
            get() = buildString {
                append("https://www.youtube-nocookie.com/embed/").append(Uri.encode(videoId))
                append("?autoplay=1&playsinline=1&rel=0&start=").append(startSeconds.coerceAtLeast(0))
                playlistId?.let { append("&list=").append(Uri.encode(it)) }
            }
    }

    var playback by mutableStateOf<Playback?>(null)
        private set

    // Video que el usuario sacó del reproductor de TV (Atrás) o que YouTube no deja mostrar: no reabrirlo
    private var skippedVideoId: String? = null

    /** Pasa al reproductor de TV si hay TV conectada y la pestaña reproduce un video de YouTube. */
    fun maybeStart(tab: BrowserTab?) {
        if (!SendaTvMode.tvConnected) {
            skippedVideoId = null
            return
        }
        if (tab == null || playback != null || starting || !tab.isMediaPlaying) return
        val videoId = SendaYouTube.youTubeVideoId(tab.url) ?: return
        if (videoId == skippedVideoId) return
        starting = true
        // Pausar y tomar la posición exacta en la que quedó: la TV sigue justo desde ahí
        tab.pauseMediaAndGetPosition { start ->
            starting = false
            if (!SendaTvMode.tvConnected) {
                tab.resumeMedia()
                return@pauseMediaAndGetPosition
            }
            val playlist = try { Uri.parse(tab.url).getQueryParameter("list") } catch (_: Exception) { null }
            Log.i(TAG, "Reproductor de TV: v=$videoId t=$start list=$playlist")
            playback = Playback(tab, videoId, start, playlist?.takeIf { it.isNotBlank() }, tab.mediaDurationSeconds)
        }
    }

    private var starting = false

    /**
     * Cierra el reproductor de TV y devuelve el video a la pestaña.
     * [positionSeconds] y [durationSeconds] son los del reproductor de TV (null si nunca llegó a reproducir).
     */
    fun finish(positionSeconds: Double?, durationSeconds: Double?, userExit: Boolean) {
        val current = playback ?: return
        playback = null
        if (userExit || positionSeconds == null) skippedVideoId = current.videoId
        val tab = current.tab
        if (positionSeconds == null) {
            // YouTube no permitió mostrar este video en su reproductor: sigue en la página como estaba
            Log.w(TAG, "El reproductor de TV no arrancó v=${current.videoId}: vuelve a la pestaña")
            tab.resumeMedia()
            return
        }
        // Con lista de reproducción la TV pudo pasar a otro video: solo se salta si sigue siendo el mismo
        val sameVideo = durationSeconds != null && current.tabDurationSeconds > 0.0 &&
            kotlin.math.abs(durationSeconds - current.tabDurationSeconds) < 1.5
        if (sameVideo) tab.seekMedia(positionSeconds)
        tab.resumeMedia()
        Log.i(TAG, "Reproductor de TV cerrado en ${positionSeconds.toInt()}s (mismo video: $sameVideo)")
    }
}
