package org.senda.browser.ui.components

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.MediaSession
import org.senda.browser.core.SendaGeckoEngine
import org.senda.browser.core.cast.SendaCastRelay
import org.senda.browser.core.cast.SendaTvMode
import org.senda.browser.core.cast.SendaTvPlayer

/** Si el video no empieza a sonar en la TV en este tiempo, vuelve a la pestaña. */
private const val START_TIMEOUT_MS = 15_000L

/**
 * Muestra [playback] en la TV como pantalla secundaria mientras esté en la composición. El teléfono no cambia.
 */
@Composable
fun TvPresentationHost(playback: SendaTvPlayer.Playback) {
    val context = LocalContext.current
    DisposableEffect(playback) {
        val display = SendaTvMode.tvDisplay()
        val presentation = display?.let {
            try {
                TvVideoPresentation(context, it, playback).also { p -> p.show() }
            } catch (e: Exception) {
                // La TV se desconectó justo ahora o Android no deja mostrar en ella
                Log.w("SendaTvPlayer", "No se pudo mostrar en la TV: ${e.message}")
                null
            }
        }
        if (presentation == null) SendaTvPlayer.finish(null, null, userExit = false)
        onDispose { presentation?.close() }
    }
}

/** Barra del teléfono mientras el video está en la TV: pausa y volver a verlo en el teléfono. */
@Composable
fun TvNowPlayingBar(playback: SendaTvPlayer.Playback, modifier: Modifier = Modifier) {
    val strings = org.senda.browser.core.LocalSendaStrings.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = strings.cast_on_tv,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = playback.tab.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            val paused = SendaTvPlayer.paused
            IconButton(onClick = {
                val controls = SendaTvPlayer.controls ?: return@IconButton
                if (paused) controls.play() else controls.pause()
            }) {
                Icon(
                    if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (paused) strings.cast_resume else strings.cast_pause,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            IconButton(onClick = { SendaTvPlayer.requestReturn?.invoke() }) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = strings.cast_back_to_phone,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/** Posición del video de la TV según el reproductor (sin scripts en la página). */
private class TvMediaClock {
    var everPlayed = false
    var playing = false
    var position = 0.0
    var duration = 0.0
    var rate = 1.0
    var at = 0L

    fun now(): Double? {
        if (!everPlayed) return null
        val elapsed = if (playing && at != 0L) (SystemClock.elapsedRealtime() - at) / 1000.0 * rate else 0.0
        val p = position + elapsed
        return if (duration > 0.0) p.coerceAtMost(duration) else p
    }

    fun mark(isPlaying: Boolean) {
        now()?.let { position = it }
        at = SystemClock.elapsedRealtime()
        playing = isPlaying
        if (isPlaying) everPlayed = true
    }
}

/**
 * Lo que se ve en la TV: el video a pantalla completa sobre negro, a la resolución de la TV. Android la retira
 * sola si la TV se desconecta (onStop), y entonces el video vuelve a la pestaña donde iba.
 */
private class TvVideoPresentation(
    context: Context,
    display: Display,
    private val playback: SendaTvPlayer.Playback
) : Presentation(context, display) {

    private val handler = Handler(Looper.getMainLooper())
    private val clock = TvMediaClock()
    private var finished = false
    private var session: GeckoSession? = null
    private var videoView: VideoView? = null
    private var relay: SendaCastRelay? = null

    private val startTimeout = Runnable {
        if (!clock.everPlayed && videoView?.isPlaying != true) finish(userExit = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        when (playback) {
            is SendaTvPlayer.Playback.YouTube -> showYouTube(root, playback)
            is SendaTvPlayer.Playback.File -> showFile(root, playback)
        }
        SendaTvPlayer.requestReturn = { finish(userExit = true) }
        handler.postDelayed(startTimeout, START_TIMEOUT_MS)
    }

    private fun showYouTube(root: FrameLayout, yt: SendaTvPlayer.Playback.YouTube) {
        var activeMedia: MediaSession? = null
        val s = SendaGeckoEngine.createSession(yt.tab.isPrivate).apply {
            permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission
                ): GeckoResult<Int> {
                    // Solo la reproducción automática: el video debe empezar solo en la TV. Nada más se concede
                    val allowed = perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                        perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                    return GeckoResult.fromValue(
                        if (allowed) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                }
            }
            mediaSessionDelegate = object : MediaSession.Delegate {
                override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
                    activeMedia = mediaSession
                }
                override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                    clock.mark(true)
                    SendaTvPlayer.paused = false
                }
                override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                    clock.mark(false)
                    SendaTvPlayer.paused = true
                }
                override fun onPositionState(session: GeckoSession, mediaSession: MediaSession, state: MediaSession.PositionState) {
                    clock.position = state.position
                    clock.duration = state.duration
                    clock.rate = if (state.playbackRate > 0.0) state.playbackRate else 1.0
                    clock.at = SystemClock.elapsedRealtime()
                }
            }
            load(GeckoSession.Loader().uri(yt.embedUrl).referrer(SendaTvPlayer.REFERRER))
        }
        session = s
        root.addView(
            GeckoView(context).apply { setSession(s) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        SendaTvPlayer.controls = object : SendaTvPlayer.Controls {
            override fun play() { activeMedia?.play() }
            override fun pause() { activeMedia?.pause() }
        }
    }

    private fun showFile(root: FrameLayout, file: SendaTvPlayer.Playback.File) {
        // El reproductor de Android no usa el proxy de Senda ni sabe el Referer: pide el video a un relé local
        // (solo 127.0.0.1) que lo trae como la página, por el mismo proxy o Tor
        val prefs = org.senda.browser.core.PreferencesManager(context)
        val r = try {
            SendaCastRelay.open(java.net.InetAddress.getByName("127.0.0.1"), file.media.referer, SendaCastRelay.proxyFrom(prefs))
        } catch (e: Exception) {
            Log.w("SendaTvPlayer", "Relé local no disponible: ${e.message}")
            finish(userExit = false)
            return
        }
        relay = r
        val view = VideoView(context)
        videoView = view
        // Centrado y con su proporción: VideoView ajusta el tamaño al video dentro del espacio disponible
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        view.setOnPreparedListener { mp ->
            if (file.startSeconds > 0) mp.seekTo(file.startSeconds * 1000L, android.media.MediaPlayer.SEEK_CLOSEST)
            mp.start()
            clock.duration = mp.duration / 1000.0
            clock.everPlayed = true
        }
        view.setOnCompletionListener { finish(userExit = false) }
        view.setOnErrorListener { _, what, extra ->
            Log.w("SendaTvPlayer", "La TV no pudo reproducir el archivo ($what/$extra)")
            clock.everPlayed = false
            finish(userExit = false)
            true
        }
        view.setVideoURI(android.net.Uri.parse(r.urlFor(file.media.url)))
        SendaTvPlayer.controls = object : SendaTvPlayer.Controls {
            override fun play() { view.start(); SendaTvPlayer.paused = false }
            override fun pause() { view.pause(); SendaTvPlayer.paused = true }
        }
    }

    private fun positionSeconds(): Double? {
        val v = videoView
        return if (v != null) {
            if (clock.everPlayed) v.currentPosition / 1000.0 else null
        } else {
            clock.now()
        }
    }

    private fun finish(userExit: Boolean) {
        if (finished) return
        finished = true
        handler.removeCallbacks(startTimeout)
        SendaTvPlayer.requestReturn = null
        SendaTvPlayer.finish(positionSeconds(), clock.duration.takeIf { it > 0.0 }, userExit)
        // Se cierra sola: con Senda en segundo plano su interfaz no se redibuja y la TV seguía mostrando el video
        handler.post { close() }
    }

    /** La TV se desconectó o Android retiró la pantalla secundaria. */
    override fun onStop() {
        finish(userExit = false)
        super.onStop()
    }

    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        finish(userExit = false)
        handler.removeCallbacks(startTimeout)
        videoView?.stopPlayback()
        session?.close()
        relay?.close()
        try { dismiss() } catch (_: Exception) {}
    }
}
