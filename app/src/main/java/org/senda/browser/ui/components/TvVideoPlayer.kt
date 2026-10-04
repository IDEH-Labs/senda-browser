package org.senda.browser.ui.components

import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.MediaSession
import org.senda.browser.core.SendaGeckoEngine
import org.senda.browser.core.cast.SendaTvMode
import org.senda.browser.core.cast.SendaTvPlayer

/** Si el reproductor de YouTube no empieza a sonar en este tiempo, el video vuelve a la página. */
private const val START_TIMEOUT_MS = 15_000L

/** Posición del video del reproductor de TV, informada por GeckoView (sin scripts en la página). */
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
 * Reproductor de YouTube a pantalla completa para la TV (ver [SendaTvPlayer]). Ocupa toda la pantalla,
 * que el modo TV ya puso en 16:9 y horizontal: en la TV se ve el video y nada más.
 */
@Composable
fun TvVideoPlayer(playback: SendaTvPlayer.Playback) {
    val clock = remember(playback) { TvMediaClock() }
    val session = remember(playback) {
        SendaGeckoEngine.createSession(playback.tab.isPrivate).apply {
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
                override fun onPlay(session: GeckoSession, mediaSession: MediaSession) = clock.mark(true)
                override fun onPause(session: GeckoSession, mediaSession: MediaSession) = clock.mark(false)
                override fun onPositionState(session: GeckoSession, mediaSession: MediaSession, state: MediaSession.PositionState) {
                    clock.position = state.position
                    clock.duration = state.duration
                    clock.rate = if (state.playbackRate > 0.0) state.playbackRate else 1.0
                    clock.at = SystemClock.elapsedRealtime()
                }
            }
            load(GeckoSession.Loader().uri(playback.embedUrl).referrer(SendaTvPlayer.REFERRER))
        }
    }

    fun finish(userExit: Boolean) =
        SendaTvPlayer.finish(clock.now(), clock.duration.takeIf { it > 0.0 }, userExit)

    // Atrás: seguir viendo en el teléfono, desde donde iba en la TV
    BackHandler { finish(userExit = true) }

    // Al cortar la transmisión el video vuelve a la pestaña
    LaunchedEffect(playback, SendaTvMode.tvConnected) {
        if (!SendaTvMode.tvConnected) finish(userExit = false)
    }

    // YouTube no deja mostrar algunos videos fuera de su web: entonces se sigue en la página
    LaunchedEffect(playback) {
        delay(START_TIMEOUT_MS)
        if (!clock.everPlayed) finish(userExit = false)
    }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { context ->
                GeckoView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setSession(session)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
