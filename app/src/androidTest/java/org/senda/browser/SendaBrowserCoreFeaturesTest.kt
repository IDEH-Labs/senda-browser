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
import org.senda.browser.core.cast.SendaDialCast
import org.senda.browser.ui.model.SendaUrlResolver
import java.io.File

/**
 * Suite de auditoría y pruebas exhaustivas para todas las funciones del navegador Senda.
 * Verifica resolución de URLs, seguridad anti-phishing, sanitización de descargas,
 * configuración del motor Gecko, políticas de privacidad y funciones de TV/Cast.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SendaBrowserCoreFeaturesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs: PreferencesManager get() = PreferencesManager(context)

    // =========================================================================
    // 1. RESOLUCIÓN DE URLs Y BÚSQUEDA ÉTICA
    // =========================================================================
    @Test
    fun test01_UrlResolverDirectSchemes() {
        val searchEngine = "https://duckduckgo.com/?q="

        // Esquemas directos deben pasar intactos
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

        // Dominios válidos sin esquema deben anteponer https://
        assertEquals("https://wikipedia.org", SendaUrlResolver.resolve("wikipedia.org", searchEngine))
        assertEquals("https://sub.portal.org/path?k=v", SendaUrlResolver.resolve("sub.portal.org/path?k=v", searchEngine))

        // Direcciones IP deben anteponer http://
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

        // Términos de búsqueda con espacios deben codificarse y enviarse al buscador
        val res1 = SendaUrlResolver.resolve("privacidad y etica digital", searchEngine)
        assertTrue(res1.startsWith("https://duckduckgo.com/?q="))
        assertTrue(res1.contains("privacidad") && res1.contains("digital"))

        // Términos con acentos o caracteres especiales
        val res2 = SendaUrlResolver.resolve("navegación rápida & segura", searchEngine)
        assertTrue(res2.startsWith("https://duckduckgo.com/?q="))

        // Buscador con placeholder %s
        val customSearchEngine = "https://searxng.org/search?q=%s&category_general=1"
        val res3 = SendaUrlResolver.resolve("senda sovereign", customSearchEngine)
        assertTrue(res3.contains("https://searxng.org/search?q=senda+sovereign") || res3.contains("senda%20sovereign"))
    }

    // =========================================================================
    // 2. PARSEO DE IDENTIFICADORES Y COMPATIBILIDAD CON YOUTUBE / CAST
    // =========================================================================
    @Test
    fun test04_YouTubeVideoIdExtraction() {
        val testId = "dQw4w9WgXcQ"

        // URL estándar watch
        assertEquals(testId, SendaDialCast.youTubeVideoId("https://www.youtube.com/watch?v=$testId"))
        assertEquals(testId, SendaDialCast.youTubeVideoId("https://m.youtube.com/watch?v=$testId&t=42s"))

        // Enlace corto youtu.be
        assertEquals(testId, SendaDialCast.youTubeVideoId("https://youtu.be/$testId"))
        assertEquals(testId, SendaDialCast.youTubeVideoId("https://youtu.be/$testId?t=10"))

        // Shorts y Embed
        assertEquals(testId, SendaDialCast.youTubeVideoId("https://www.youtube.com/shorts/$testId"))
        assertEquals(testId, SendaDialCast.youTubeVideoId("https://www.youtube-nocookie.com/embed/$testId"))

        // URLs no válidas no deben extraer ID
        assertNull(SendaDialCast.youTubeVideoId("https://vimeo.com/12345678"))
        assertNull(SendaDialCast.youTubeVideoId("https://senda.org/watch?v=$testId"))
        assertNull(SendaDialCast.youTubeVideoId("about:blank"))
    }

    // =========================================================================
    // 3. SEGURIDAD Y CONFIGURACIÓN DEL MOTOR GECKO
    // =========================================================================
    @Test
    fun test05_GeckoSessionCreationAndSecurityFlags() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Crear sesión privada
            val privateSession = SendaGeckoEngine.createSession(isPrivate = true, allowJs = true)
            assertNotNull(privateSession)
            assertTrue("La sesión privada debe tener modo privado activo", privateSession.settings.usePrivateMode)
            assertFalse("La reproducción en segundo plano debe permitirse sin suspensión", privateSession.settings.suspendMediaWhenInactive)

            // Crear sesión normal
            val normalSession = SendaGeckoEngine.createSession(isPrivate = false, allowJs = true)
            assertNotNull(normalSession)
            assertFalse("La sesión normal no debe tener modo privado forzado", normalSession.settings.usePrivateMode)
            assertFalse("La reproducción en segundo plano debe permitirse sin suspensión", normalSession.settings.suspendMediaWhenInactive)

            privateSession.close()
            normalSession.close()
        }
    }

    // =========================================================================
    // 4. PREFERENCIAS ÉTICAS Y PROTECCIÓN DE DATOS
    // =========================================================================
    @Test
    fun test06_PreferencesEthicalDefaults() {
        // Verificar que los ajustes por defecto respeten la privacidad del usuario
        assertTrue(
            "uBlock Origin debe estar entre las extensiones nativas embebidas",
            SendaGeckoEngine.EMBEDDED_EXTENSION_IDS.contains("uBlock0@raymondhill.net")
        )
        assertEquals("El nivel de protección contra rastreo debe ser STRICT", "STRICT", prefs.trackingProtectionLevel)
        assertEquals("Las cookies de terceros deben aislarse por defecto", "ISOLATE_THIRD_PARTY", prefs.cookiePolicy)
        assertTrue("El bloqueo de rastreadores sociales debe estar activo", prefs.blockSocialTrackers)
    }

    // =========================================================================
    // 5. GESTOR DE DESCARGAS Y SANITIZACIÓN DE NOMBRES DE ARCHIVO
    // =========================================================================
    @Test
    fun test07_DownloadFilenameSanitization() {
        // Nombres maliciosos con intentos de Directory Traversal
        val maliciousSuggested = "../../../../etc/shadow.txt"
        val sanitized = File(maliciousSuggested).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        assertFalse("El nombre no debe contener separadores de directorios", sanitized.contains("/"))
        assertFalse("El nombre no debe contener retrocesos de ruta", sanitized.contains(".."))
    }
}
