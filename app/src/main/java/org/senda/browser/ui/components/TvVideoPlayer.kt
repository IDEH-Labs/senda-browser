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
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

/** If the video does not start playing on the TV within this time, it goes back to the tab. */
private const val START_TIMEOUT_MS = 15_000L

/**
 * Shows [playback] on the TV as a secondary display while it is in the composition. The phone does not change.
 */
@Composable
fun TvPresentationHost(playback: SendaTvPlayer.Playback) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(playback) {
        val display = SendaTvMode.tvDisplay()
        val presentation = display?.let {
            try {
                TvVideoPresentation(context, it, playback).also { p -> p.show() }
            } catch (e: Exception) {
                // The TV just disconnected or Android does not allow showing on it
                Log.w("SendaTvPlayer", "No se pudo mostrar en la TV: ${e.message}")
                null
            }
        }
        if (presentation == null) SendaTvPlayer.finish(null, null, userExit = false)
        // When leaving Senda the TV shows the phone again. From the lifecycle: in the background Senda does not
        // redraw and the composition would not notice
        // With the phone screen off (put aside or locked) the video stays on the TV
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && power?.isInteractive != false) SendaTvPlayer.requestLeave?.invoke()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            presentation?.close()
        }
    }
}

/**
 * TV mode inside the cast dialog: which TV it is connected to, the video shown on it (pause and "Watch on
 * the phone") and "Disconnect TV". Outside of this there is nothing fixed on screen: the colored cast button
 * shows that the TV is connected.
 */
@Composable
fun TvControlsCard(onDone: () -> Unit) {
    val strings = org.senda.browser.core.LocalSendaStrings.current
    val context = LocalContext.current
    val tvName = SendaTvMode.tvName ?: ""
    val playback = SendaTvPlayer.playback
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = strings.cast_connected_to.replace("{tv}", tvName),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = playback?.tab?.title
                            ?: if (org.senda.browser.core.PreferencesManager(context).tvModeEnabled) strings.cast_videos_go_to_tv
                            else strings.cast_tv_mode_off_hint,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (playback != null) {
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
                    IconButton(onClick = {
                        SendaTvPlayer.requestReturn?.invoke(true)
                        onDone()
                    }) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = strings.cast_back_to_phone,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = {
                    SendaTvMode.disconnect(context)
                    onDone()
                }) {
                    Icon(Icons.Default.CastConnected, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.cast_disconnect_tv)
                }
            }
        }
    }
}

/** Position of the TV video according to the player (no scripts in the page). */
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
 * What is shown on the TV: the video full screen over black, at the TV's resolution. Android removes it
 * on its own if the TV disconnects (onStop), and then the video goes back to the tab where it was.
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
        SendaTvPlayer.requestReturn = { resume -> finish(userExit = true, resumeOnPhone = resume) }
        SendaTvPlayer.requestLeave = {
            SendaTvPlayer.rememberResume(playback.key, positionSeconds())
            finish(userExit = false, resumeOnPhone = false)
        }
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
                    // Autoplay only: the video must start on its own on the TV. Nothing else is granted
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
        // Android's player does not use Senda's proxy and does not know the Referer: it asks a local relay for the video
        // (127.0.0.1 only), which fetches it the way the page did, through the same proxy or Tor
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
        // Centered and with its aspect ratio: VideoView fits the size to the video within the available space
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

    private fun finish(userExit: Boolean, resumeOnPhone: Boolean = true) {
        if (finished) return
        finished = true
        handler.removeCallbacks(startTimeout)
        SendaTvPlayer.requestReturn = null
        SendaTvPlayer.requestLeave = null
        SendaTvPlayer.finish(positionSeconds(), clock.duration.takeIf { it > 0.0 }, userExit, resumeOnPhone)
        // It closes on its own: with Senda in the background its UI is not redrawn and the TV kept showing the video
        handler.post { close() }
    }

    /** The TV disconnected or Android removed the secondary display: the video stays paused, it does not sound on the phone. */
    override fun onStop() {
        finish(userExit = false, resumeOnPhone = false)
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
