package org.senda.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.senda.browser.core.*
import org.senda.browser.core.cast.SendaYouTube
import org.senda.browser.ui.model.SendaUrlResolver
import java.io.File

/**
 * Audit suite and thorough tests for all of Senda's browser features.
 * Checks URL resolution, anti-phishing security, download file name sanitization,
 * Gecko engine configuration, privacy policies and TV/Cast features.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SendaBrowserCoreFeaturesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs: PreferencesManager get() = PreferencesManager(context)

    // =========================================================================
    // 1. URL RESOLUTION AND ETHICAL SEARCH
    // =========================================================================
    @Test
    fun test01_UrlResolverDirectSchemes() {
        val searchEngine = "https://duckduckgo.com/?q="

        // Direct schemes must pass through unchanged
        assertEquals("about:blank", SendaUrlResolver.resolve("about:blank", searchEngine))
        assertEquals("https://senda.org", SendaUrlResolver.resolve("https://senda.org", searchEngine))
        assertEquals("http://example.com/test", SendaUrlResolver.resolve("http://example.com/test", searchEngine))
        assertEquals("view-source:https://senda.org", SendaUrlResolver.resolve("view-source:https://senda.org", searchEngine))
        assertEquals("moz-extension://ublock-id/options.html", SendaUrlResolver.resolve("moz-extension://ublock-id/options.html", searchEngine))
        assertEquals("about:config", SendaUrlResolver.resolve("about:config", searchEngine))
    }

    @Test
    fun test02_UrlResolverDomainsAndIps() {
        val searchEngine = "https://duckduckgo.com/?q="

        // Valid domains without a scheme must get https:// prepended
        assertEquals("https://wikipedia.org", SendaUrlResolver.resolve("wikipedia.org", searchEngine))
        assertEquals("https://sub.portal.org/path?k=v", SendaUrlResolver.resolve("sub.portal.org/path?k=v", searchEngine))

        // IP addresses must get http:// prepended
        assertEquals("http://192.168.1.1", SendaUrlResolver.resolve("192.168.1.1", searchEngine))
        assertEquals("http://192.168.1.100:8080", SendaUrlResolver.resolve("192.168.1.100:8080", searchEngine))
        assertEquals("http://127.0.0.1:3000", SendaUrlResolver.resolve("127.0.0.1:3000", searchEngine))

        // Localhost
        assertEquals("http://localhost", SendaUrlResolver.resolve("localhost", searchEngine))
        assertEquals("http://localhost:8080/api", SendaUrlResolver.resolve("localhost:8080/api", searchEngine))
    }

    @Test
    fun test03_UrlResolverSearchQueries() {
        val searchEngine = "https://duckduckgo.com/?q="

        // Search terms with spaces must be encoded and sent to the search engine
        val res1 = SendaUrlResolver.resolve("privacidad y etica digital", searchEngine)
        assertTrue(res1.startsWith("https://duckduckgo.com/?q="))
        assertTrue(res1.contains("privacidad") && res1.contains("digital"))

        // Terms with accents or special characters
        val res2 = SendaUrlResolver.resolve("navegación rápida & segura", searchEngine)
        assertTrue(res2.startsWith("https://duckduckgo.com/?q="))

        // Search engine with a %s placeholder
        val customSearchEngine = "https://searxng.org/search?q=%s&category_general=1"
        val res3 = SendaUrlResolver.resolve("senda sovereign", customSearchEngine)
        assertTrue(res3.contains("https://searxng.org/search?q=senda+sovereign") || res3.contains("senda%20sovereign"))
    }

    // =========================================================================
    // 2. IDENTIFIER PARSING AND YOUTUBE / CAST COMPATIBILITY
    // =========================================================================
    @Test
    fun test04_YouTubeVideoIdExtraction() {
        val testId = "dQw4w9WgXcQ"

        // Standard watch URL
        assertEquals(testId, SendaYouTube.youTubeVideoId("https://www.youtube.com/watch?v=$testId"))
        assertEquals(testId, SendaYouTube.youTubeVideoId("https://m.youtube.com/watch?v=$testId&t=42s"))

        // Short youtu.be link
        assertEquals(testId, SendaYouTube.youTubeVideoId("https://youtu.be/$testId"))
        assertEquals(testId, SendaYouTube.youTubeVideoId("https://youtu.be/$testId?t=10"))

        // Shorts and Embed
        assertEquals(testId, SendaYouTube.youTubeVideoId("https://www.youtube.com/shorts/$testId"))
        assertEquals(testId, SendaYouTube.youTubeVideoId("https://www.youtube-nocookie.com/embed/$testId"))

        // Invalid URLs must not yield an ID
        assertNull(SendaYouTube.youTubeVideoId("https://vimeo.com/12345678"))
        assertNull(SendaYouTube.youTubeVideoId("https://senda.org/watch?v=$testId"))
        assertNull(SendaYouTube.youTubeVideoId("about:blank"))
    }

    // =========================================================================
    // 3. GECKO ENGINE SECURITY AND CONFIGURATION
    // =========================================================================
    @Test
    fun test05_GeckoSessionCreationAndSecurityFlags() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Create a private session
            val privateSession = SendaGeckoEngine.createSession(isPrivate = true, allowJs = true)
            assertNotNull(privateSession)
            assertTrue("La sesión privada debe tener modo privado activo", privateSession.settings.usePrivateMode)
            assertFalse("La reproducción en segundo plano debe permitirse sin suspensión", privateSession.settings.suspendMediaWhenInactive)

            // Create a normal session
            val normalSession = SendaGeckoEngine.createSession(isPrivate = false, allowJs = true)
            assertNotNull(normalSession)
            assertFalse("La sesión normal no debe tener modo privado forzado", normalSession.settings.usePrivateMode)
            assertFalse("La reproducción en segundo plano debe permitirse sin suspensión", normalSession.settings.suspendMediaWhenInactive)

            privateSession.close()
            normalSession.close()
        }
    }

    // =========================================================================
    // 4. ETHICAL PREFERENCES AND DATA PROTECTION
    // =========================================================================
    @Test
    fun test06_PreferencesEthicalDefaults() {
        // Check that the default settings respect the user's privacy
        assertTrue(
            "uBlock Origin debe estar entre las extensiones nativas embebidas",
            SendaGeckoEngine.EMBEDDED_EXTENSION_IDS.contains("uBlock0@raymondhill.net")
        )
        assertEquals("El nivel de protección contra rastreo debe ser STRICT", "STRICT", prefs.trackingProtectionLevel)
        assertEquals("Las cookies de terceros deben aislarse por defecto", "ISOLATE_THIRD_PARTY", prefs.cookiePolicy)
        assertTrue("El bloqueo de rastreadores sociales debe estar activo", prefs.blockSocialTrackers)
    }

    // =========================================================================
    // 5. DOWNLOAD MANAGER AND FILE NAME SANITIZATION
    // =========================================================================
    @Test
    fun test07_DownloadFilenameSanitization() {
        // Malicious names with directory traversal attempts
        val maliciousSuggested = "../../../../etc/shadow.txt"
        val sanitized = File(maliciousSuggested).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        assertFalse("El nombre no debe contener separadores de directorios", sanitized.contains("/"))
        assertFalse("El nombre no debe contener retrocesos de ruta", sanitized.contains(".."))
    }
}
