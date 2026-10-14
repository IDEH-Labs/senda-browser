package org.senda.browser.core.cast

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sending in original quality: who is trusted as a TV, and what type the TV is told. */
class SendaDlnaCastTest {
    private val tv = InetAddress.getByName("192.168.1.8")

    private fun media(url: String, mime: String = "") = SendaMediaCatalog.Media(url, mime, -1L, null, 0L)

    @Test
    fun onlyTheTvThatAnsweredIsTrusted() {
        assertTrue(isUrlOnResponder("http://192.168.1.8:1816/desc.xml", tv))
        // Another host, https or a public responder could send the file anywhere
        assertFalse(isUrlOnResponder("http://192.168.1.9:1816/desc.xml", tv))
        assertFalse(isUrlOnResponder("http://example.com/desc.xml", tv))
        assertFalse(isUrlOnResponder("https://192.168.1.8/desc.xml", tv))
        assertFalse(isUrlOnResponder("http://8.8.8.8/desc.xml", InetAddress.getByName("8.8.8.8")))
        assertFalse(isUrlOnResponder(null, tv))
    }

    @Test
    fun imagesAreToldApartFromVideos() {
        assertTrue(media("https://a.example/photo.JPG?w=1").isImage)
        assertTrue(media("https://a.example/p", "image/png").isImage)
        assertFalse(media("https://a.example/movie.mp4").isImage)
        assertEquals("image/jpeg", media("https://a.example/photo.jpeg").tvMime)
        assertEquals("image/png", media("https://a.example/p", "image/png").tvMime)
        assertEquals("video/mp4", media("https://a.example/movie.mp4", "application/octet-stream").tvMime)
        assertEquals("application/vnd.apple.mpegurl", media("https://a.example/live.m3u8").tvMime)
    }

    @Test
    fun anImageOpenInTheTabCanBeSent() {
        assertTrue(SendaMediaCatalog.imageFor("https://a.example/pic.webp") != null)
        assertTrue(SendaMediaCatalog.imageFor("https://a.example/page.html") == null)
        assertTrue(SendaMediaCatalog.imageFor("file:///sdcard/pic.png") == null)
    }

    @Test
    fun aLiveBroadcastSendsEveryQuality() {
        val page = "https://news.example/live"
        val cdn = "https://cdn.example/stream/abc"
        SendaMediaCatalog.add(page, media("$cdn/master.m3u8?token=1", "application/x-mpegURL"))
        // The player then reloads one quality's playlist every few seconds
        SendaMediaCatalog.add(page, media("$cdn/variant/1/bandwidth/1927200.m3u8?token=1", "application/x-mpegURL"))
        assertEquals("$cdn/master.m3u8?token=1", SendaMediaCatalog.bestFor(page)?.url)
        SendaMediaCatalog.clear()
    }

    @Test
    fun aLiveBroadcastEntryGivesTheTvItsOwnSession() {
        val page = "https://news.example/live2"
        val entry = SendaMediaCatalog.Media("https://mdstrm.example/live-stream-playlist/1.m3u8?access_token=t",
            "application/vnd.apple.mpegurl", -1L, null, 0L, isEntry = true)
        SendaMediaCatalog.add(page, entry)
        SendaMediaCatalog.add(page, media("https://cdn.example/stream/s1/master.m3u8", "application/x-mpegURL"))
        SendaMediaCatalog.add(page, media("https://cdn.example/stream/s1/variant/1.m3u8", "application/x-mpegURL"))
        assertEquals(entry.url, SendaMediaCatalog.bestFor(page)?.url)
        SendaMediaCatalog.clear()
    }
}
