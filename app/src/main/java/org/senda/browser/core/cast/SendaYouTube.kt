package org.senda.browser.core.cast

import android.net.Uri

/** Recognizes YouTube video links (for the TV player while mirroring). */
object SendaYouTube {

    /** Extracts the video identifier from any YouTube link (watch, youtu.be, shorts, live, embed). */
    fun youTubeVideoId(url: String): String? {
        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val idPattern = Regex("^[A-Za-z0-9_-]{11}$")
        val id = when {
            host == "youtu.be" -> uri.pathSegments.firstOrNull()
            host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> {
                val segments = uri.pathSegments
                when (segments.firstOrNull()) {
                    "watch" -> uri.getQueryParameter("v")
                    "shorts", "live", "embed", "v" -> segments.getOrNull(1)
                    else -> null
                }
            }
            else -> null
        }
        return id?.takeIf { idPattern.matches(it) }
    }
}
