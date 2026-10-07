package org.senda.browser.core.cast

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.senda.browser.ui.model.BrowserTab

/**
 * Video on the TV as a secondary display: while the phone is mirrored to a TV (Miracast), the video
 * playing in Senda moves to the TV full screen, in 16:9 and at the TV's resolution, from the same second.
 * The phone does not change (it stays in portrait and you can keep browsing): Android shows on the TV what Senda
 * draws for it ([org.senda.browser.ui.components.TvPresentationHost]) instead of the phone's mirror.
 *
 * YouTube does not deliver a file: its official player (youtube-nocookie.com) is used in a separate session, without
 * injecting anything into pages. Other videos (MP4, HLS…) play with Android's player from
 * the file [SendaMediaCatalog] detected. When it ends, the video continues in the tab where it left off.
 */
object SendaTvPlayer {

    private const val TAG = "SendaTvPlayer"

    /**
     * YouTube requires whoever shows its player to identify itself with a Referer; for apps, with
     * https://<package>. Without it, it answers "Error 153". It is Senda's real identity, not an impersonation.
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

    /** What the phone can ask of the TV player (registered by the player being shown). */
    interface Controls {
        fun play()
        fun pause()
    }

    var playback by mutableStateOf<Playback?>(null)
        private set
    var paused by mutableStateOf(false)
    var controls: Controls? = null

    // A video the user returned to the phone or that the TV could not show: do not move it on its own again
    private var skippedKey: String? = null
    // Where a video was on the TV when it was removed on leaving Senda: on return it continues from there
    private val resumeAt = HashMap<String, Int>()
    private var starting = false

    /**
     * While mirroring to a TV, the page's video moves to the TV on its own as soon as it exists (when opening a YouTube video or
     * when the page loads its file), without buttons: that is why the TV was connected. It does not wait for it to play on the
     * phone because Gecko does not always report it (MediaSession). It does not move again a video the user
     * returned to the phone.
     */
    fun autoStart(tab: BrowserTab?, enabled: Boolean) {
        if (!SendaTvMode.tvConnected) {
            skippedKey = null
            return
        }
        // Private tabs do not move to the TV on their own: Senda also hides them while mirroring
        if (!enabled || tab == null || tab.isPrivate) return
        val key = SendaYouTube.youTubeVideoId(tab.url) ?: SendaMediaCatalog.bestFor(tab.url)?.url ?: return
        if (key == skippedKey || key == playback?.key) return
        // Another video while the TV is showing one: the new one replaces it
        if (playback != null) {
            requestReturn?.invoke()
            if (playback != null) return
        }
        start(tab)
    }

    /** Shows [tab]'s video on the TV from where it is (the user asked for it or it started playing). */
    fun start(tab: BrowserTab) {
        if (!SendaTvMode.tvConnected || playback != null || starting) return
        val videoId = SendaYouTube.youTubeVideoId(tab.url)
        val media = if (videoId == null) SendaMediaCatalog.bestFor(tab.url) else null
        val key = videoId ?: media?.url ?: return
        starting = true
        // Pause and take the exact position where it stopped: the TV continues right from there
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
     * Closes the video on the TV and returns it to the tab. [positionSeconds] and [durationSeconds] come from the
     * TV player (null if it never played). [userExit]: the user asked for it; it is not moved again.
     * [resumeOnPhone]: false when leaving Senda (it stays paused).
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
        // With a playlist the TV may have moved to another video: it only seeks if it is still the same one
        val sameVideo = durationSeconds != null && current.tabDurationSeconds > 0.0 &&
            kotlin.math.abs(durationSeconds - current.tabDurationSeconds) < 1.5
        if (sameVideo) tab.seekMedia(positionSeconds)
        // When leaving Senda the video must not play on the phone while another app is in use
        if (resumeOnPhone) tab.resumeMedia()
        Log.i(TAG, "Video de vuelta al teléfono en ${positionSeconds.toInt()}s (mismo video: $sameVideo)")
    }

    /** Asks the TV player to return the video to the phone (with its position). */
    var requestReturn: (() -> Unit)? = null

    /**
     * Senda went to the background: the TV shows the phone again (another app, e.g. Telegram). Where the video
     * was is remembered, to continue from there when returning to Senda.
     */
    var requestLeave: (() -> Unit)? = null

    internal fun rememberResume(key: String, seconds: Double?) {
        if (seconds != null && seconds > 1.0) resumeAt[key] = seconds.toInt()
    }
}
