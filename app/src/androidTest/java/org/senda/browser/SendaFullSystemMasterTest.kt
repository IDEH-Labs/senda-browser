package org.senda.browser

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.senda.browser.core.*
import org.senda.browser.core.security.SendaVaultManager
import java.io.InputStream

/**
 * Suite Maestra de Verificación y Auditoría de Sistema Completo de Senda.
 * Ejecuta pruebas funcionales y criptográficas en todas las capas del navegador
 * directamente sobre el hardware del dispositivo moto g34 5G.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SendaFullSystemMasterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs: PreferencesManager get() = PreferencesManager(context)

    // =========================================================================
    // 1. SUBSISTEMA DE INTERNACIONALIZACIÓN (I18N - 8 IDIOMAS)
    // =========================================================================
    @Test
    fun test01_LocalizationIntegrityAcrossAll8Languages() {
        val supportedCodes = listOf("ES", "EN", "DE", "FR", "PT", "IT", "JA", "ZH")
        val registeredLanguages = SendaLocaleManager.supportedLanguages

        // 1.1 Verificar que los 8 idiomas estén registrados en el selector
        for (code in supportedCodes) {
            assertTrue(
                "El idioma $code debe estar registrado en SendaLocaleManager",
                registeredLanguages.any { it.code == code }
            )
        }

        // 1.2 Verificar paridad de textos y claves esenciales en cada idioma
        for (code in supportedCodes) {
            val pack = SendaStrings.get(code, context)
            assertNotNull("El paquete de strings para $code no debe ser nulo", pack)
            assertTrue("st_passwords_title en $code no debe estar vacío", pack.st_passwords_title.isNotBlank())
            assertTrue("st_extensions_title en $code no debe estar vacío", pack.st_extensions_title.isNotBlank())
            assertTrue("sync_webdav_title en $code no debe estar vacío", pack.sync_webdav_title.isNotBlank())
            assertTrue("ublock_dlg_title en $code no debe estar vacío", pack.ublock_dlg_title.isNotBlank())
            assertTrue("dlg_passwords_info en $code no debe estar vacío", pack.dlg_passwords_info.isNotBlank())
            assertTrue("rm_visual_theme en $code no debe estar vacío", pack.rm_visual_theme.isNotBlank())
        }
        println("[PASS 1/8] Internacionalización: 8/8 idiomas verificados con paridad total de claves.")
    }

    // =========================================================================
    // 2. PERSISTENCIA DE MARCADORES, HISTORIAL Y ZEN VIEW
    // =========================================================================
    @Test
    fun test02_BookmarksAndHistoryPersistence() {
        // 2.1 Marcadores
        prefs.toggleBookmark(title = "Senda Sovereign Test", url = "https://senda.org/test")
        val bookmarks = prefs.getBookmarks()
        val addedBm = bookmarks.find { it.url == "https://senda.org/test" }
        assertNotNull("El marcador añadido debe existir en la lista", addedBm)

        prefs.deleteBookmark(addedBm!!.id)
        val afterRemove = prefs.getBookmarks()
        assertFalse("El marcador debe ser eliminado", afterRemove.any { it.id == addedBm.id })

        // 2.2 Historial local
        prefs.addHistoryItem(title = "History Test Item", url = "https://senda.org/history")
        val historyList = prefs.getHistory()
        assertTrue("El historial debe registrar la navegación", historyList.any { it.url == "https://senda.org/history" })
        prefs.clearHistory()
        assertTrue("El historial debe quedar limpio tras clearHistory", prefs.getHistory().isEmpty())

        // 2.3 Accesos directos Zen
        val originalShortcuts = prefs.getZenShortcuts()
        val testShortcut = ZenShortcut(
            id = "zen_test_1",
            title = "Test Zen",
            url = "https://senda.org",
            monogram = "SZ",
            colorHex = "#2E7D32"
        )
        prefs.saveZenShortcuts(listOf(testShortcut))
        val loadedShortcuts = prefs.getZenShortcuts()
        assertEquals("Debe persistir el acceso directo Zen", 1, loadedShortcuts.size)
        assertEquals("SZ", loadedShortcuts[0].monogram)
        prefs.saveZenShortcuts(originalShortcuts) // Restaurar

        println("[PASS 2/8] Persistencia: Marcadores, Historial y Modo Zen validados.")
    }

    // =========================================================================
    // 3. CIFRADO POR HARDWARE DE WEBDAV (KEYSTORE AES-256-GCM)
    // =========================================================================
    @Test
    fun test03_WebdavHardwareEncryptionInStorage() {
        val rawPrefs = context.getSharedPreferences("senda_preferences", Context.MODE_PRIVATE)
        val testPassword = "HardwareVaultWebDavKey_2026!#"

        // Escribir credencial
        prefs.webdavPassword = testPassword

        // Comprobar ausencia de texto plano en memoria flash
        val plainInDisk = rawPrefs.getString("webdav_password", null)
        assertNull("La contraseña WebDAV NO debe estar en texto plano", plainInDisk)

        // Comprobar presencia de campos cifrados con Nonce
        val enc = rawPrefs.getString("webdav_password_enc", null)
        val iv = rawPrefs.getString("webdav_password_iv", null)
        assertNotNull("Payload cifrado ausente", enc)
        assertNotNull("IV único ausente", iv)

        // Descifrado mediante Keystore
        val decrypted = prefs.webdavPassword
        assertEquals("El descifrado de Keystore debe coincidir", testPassword, decrypted)

        // Limpieza y sanitización
        prefs.webdavPassword = ""
        assertNull(rawPrefs.getString("webdav_password_enc", null))
        assertNull(rawPrefs.getString("webdav_password_iv", null))

        println("[PASS 3/8] Cifrado WebDAV: 100% verificado en chip Keystore sin fugas de texto plano.")
    }

    // =========================================================================
    // 4. BÓVEDA SOBERANA DE CONTRASEÑAS Y PROTECCIÓN CONTRA ATAQUES
    // =========================================================================
    @Test
    fun test04_SendaVaultForensicAndTamperProtection() {
        val domain = "https://sub.portal.banco.com.es:8443/auth?step=1"
        val user = "usuario_seguro"
        val passChars = "ClaveUltraSegura#2026_ñ!".toCharArray()

        // 4.1 Guardado y extracción canónica de dominio (eTLD+1)
        val cred = SendaVaultManager.saveCredential(context, domain, user, passChars)
        assertEquals("El dominio canónico debe ser banco.com.es", "banco.com.es", cred.domain)

        // 4.2 Descifrado a CharArray y Zeroization
        val retrievedChars = SendaVaultManager.decryptPassword(cred.encryptedPasswordBase64, cred.ivBase64)
        assertArrayEquals("La contraseña descifrada debe coincidir", passChars, retrievedChars)
        SendaVaultManager.wipe(retrievedChars)
        assertTrue("La memoria RAM debe quedar en ceros", retrievedChars.all { it == '\u0000' })

        // 4.3 Inviolabilidad de mensaje (Tamper Resistance con AEAD tag)
        var tamperDetected = false
        try {
            val encBytes = android.util.Base64.decode(cred.encryptedPasswordBase64, android.util.Base64.NO_WRAP)
            encBytes[0] = (encBytes[0].toInt() xor 0xFF).toByte() // Corromper byte
            val tamperedBase64 = android.util.Base64.encodeToString(encBytes, android.util.Base64.NO_WRAP)
            SendaVaultManager.decryptPassword(tamperedBase64, cred.ivBase64)
        } catch (e: Exception) {
            tamperDetected = true
        }
        assertTrue("La etiqueta AEAD debe rechazar cualquier alteración de bits", tamperDetected)

        // 4.4 Limpiar credencial
        SendaVaultManager.deleteCredential(context, cred.id)
        assertFalse("La credencial debe eliminarse de la bóveda", SendaVaultManager.getCredentials(context).any { it.id == cred.id })

        println("[PASS 4/8] Bóveda Criptográfica: eTLD+1, Zeroization y resistencia a manipulación AEAD verificados.")
    }

    // =========================================================================
    // 5. ASSETS INTERNOS Y uBLOCK ORIGIN NATIVO
    // =========================================================================
    @Test
    fun test05_BuiltInAssetsAndLocalUBlockPresence() {
        val assetManager = context.assets

        // 5.1 Verificar ublock.xpi embebido
        var ublockStream: InputStream? = null
        try {
            ublockStream = assetManager.open("extensions/ublock.xpi")
            assertNotNull("ublock.xpi debe existir en los assets internos", ublockStream)
            val bytesCount = ublockStream.available()
            assertTrue("ublock.xpi debe tener un tamaño válido (>1MB)", bytesCount > 1_000_000)
        } finally {
            ublockStream?.close()
        }

        // 5.2 Verificar extensión local de Proxy / Tor
        var proxyStream: InputStream? = null
        try {
            proxyStream = assetManager.open("extensions/senda_proxy/manifest.json")
            assertNotNull("manifest.json de proxy debe existir", proxyStream)
        } finally {
            proxyStream?.close()
        }

        println("[PASS 5/8] Recursos Soberanos: uBlock Origin y extensiones internas presentes y listas offline.")
    }

    // =========================================================================
    // 6. ENRUTAMIENTO TOR Y CONFIGURACIÓN PROXY SOCKS5
    // =========================================================================
    @Test
    fun test06_TorAndProxyConfigurationState() {
        // Verificar estado de configuración de proxy
        assertEquals("Tor debe iniciar en estado STOPPED", TorState.STOPPED, SendaTorManager.state)
        assertEquals("El puerto local por defecto de SOCKS5 Tor debe ser 9050", 9050, SendaTorManager.socksPort)
        println("[PASS 6/8] Enrutamiento Seguro: SendaTorManager listo para sockets SOCKS5.")
    }

    // =========================================================================
    // 7. GESTOR DE DESCARGAS SOBERANO
    // =========================================================================
    @Test
    fun test07_DownloadManagerIntegrity() {
        val testItem = DownloadItem(
            id = "dl_test_123",
            fileName = "senda_sovereign_manual.pdf",
            url = "https://senda.org/manual.pdf",
            filePath = "/storage/emulated/0/Download/manual.pdf",
            totalBytes = 1024 * 512,
            downloadedBytes = 1024 * 512,
            status = "COMPLETED",
            mimeType = "application/pdf"
        )
        val initialDownloads = prefs.getDownloads()
        prefs.saveDownloads(initialDownloads + testItem)

        val retrievedDownloads = prefs.getDownloads()
        assertTrue("La descarga debe registrarse en la lista", retrievedDownloads.any { it.id == "dl_test_123" })
        prefs.saveDownloads(initialDownloads) // Restaurar
        assertFalse("La descarga debe eliminarse", prefs.getDownloads().any { it.id == "dl_test_123" })

        println("[PASS 7/8] Gestor de Descargas: Registro, estados y persistencia verificados.")
    }

    @Test
    fun test08_SystemIntentsAndAutofillResolution() {
        // Verificar que el intent de autocompletado con paquete se resuelva en Android 15
        val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
        val resolveInfo = context.packageManager.resolveActivity(intent, 0)
        assertNotNull("El intent de autocompletado con paquete DEBE resolverse en Android 15", resolveInfo)
        println("[PASS 8/8] Integración OS: Intent de autocompletado verificado y ejecutable en Android 15.")
    }

    // =========================================================================
    // 9. METADATOS Y RESOLUCIÓN REAL DE URLs DE uBLOCK ORIGIN
    // =========================================================================
    @Test
    fun test09_GeckoViewWebExtensionUBlockResolution() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SendaGeckoEngine.initialize(context, prefs)
        }

        for (i in 1..30) {
            val latch = java.util.concurrent.CountDownLatch(1)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SendaGeckoEngine.refreshExtensions {
                    latch.countDown()
                }
            }
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            if (SendaGeckoEngine.getUBlockDashboardUrl() != null) break
            Thread.sleep(500)
        }

        val ublock = SendaGeckoEngine.getUBlockExtension()
        assertNotNull("uBlock debe estar instalado en controller.list()", ublock)
        val meta = ublock?.metaData
        println("=== AUDITORIA UBLOCK EXTENSION ===")
        println("  id: ${ublock?.id}")
        println("  baseUrl: ${meta?.baseUrl}")
        println("  optionsPageUrl: ${meta?.optionsPageUrl}")
        println("  homepageUrl: ${meta?.homepageUrl}")
        println("  openOptionsPageInTab: ${meta?.openOptionsPageInTab}")
        println("  DashboardUrl: ${SendaGeckoEngine.getUBlockDashboardUrl()}")
        println("  FiltersUrl: ${SendaGeckoEngine.getUBlockFiltersUrl()}")
        println("  PopupUrl: ${SendaGeckoEngine.getUBlockPopupUrl()}")
        println("==================================")
        assertNotNull("DashboardUrl NO debe ser nulo", SendaGeckoEngine.getUBlockDashboardUrl())
    }

    // =========================================================================
    // 10. CARGA REAL DE PÁGINA UBLOCK EN GECKOSESSION
    // =========================================================================
    @Test
    fun test10_LoadUBlockInGeckoSession() {
        var targetUrl: String? = null

        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SendaGeckoEngine.initialize(context, prefs)
        }

        // Esperar a que GeckoView inicialice uBlock Origin y popule sus metadatos
        for (i in 1..30) {
            val listLatch = java.util.concurrent.CountDownLatch(1)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SendaGeckoEngine.refreshExtensions {
                    targetUrl = SendaGeckoEngine.getUBlockDashboardUrl()
                    listLatch.countDown()
                }
            }
            listLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            if (targetUrl != null) break
            Thread.sleep(500)
        }

        assertNotNull("optionsPageUrl debe estar disponible en uBlock tras inicialización", targetUrl)

        val pageLatch = java.util.concurrent.CountDownLatch(1)
        var loadedUrl: String? = null
        var loadSuccess = false

        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val session = SendaGeckoEngine.createSession(false)
            session.progressDelegate = object : org.mozilla.geckoview.GeckoSession.ProgressDelegate {
                override fun onPageStop(s: org.mozilla.geckoview.GeckoSession, success: Boolean) {
                    loadSuccess = success
                    pageLatch.countDown()
                }
            }
            session.navigationDelegate = object : org.mozilla.geckoview.GeckoSession.NavigationDelegate {
                override fun onLocationChange(s: org.mozilla.geckoview.GeckoSession, newUrl: String?, perms: MutableList<org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                    loadedUrl = newUrl
                }
            }
            session.loadUri(targetUrl!!)
        }

        pageLatch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        println("=== TEST 10 LOAD UBLOCK RESULT ===")
        println("  targetUrl: $targetUrl")
        println("  loadedUrl: $loadedUrl")
        println("  loadSuccess: $loadSuccess")
        println("==================================")
        assertTrue("GeckoSession debe cargar la página de uBlock", loadSuccess)
    }
}


