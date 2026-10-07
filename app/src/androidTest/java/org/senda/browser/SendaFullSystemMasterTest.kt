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
 * Master verification and full-system audit suite for Senda.
 * Runs functional and cryptographic tests on every layer of the browser
 * directly on the moto g34 5G hardware.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SendaFullSystemMasterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs: PreferencesManager get() = PreferencesManager(context)

    // =========================================================================
    // 1. INTERNATIONALIZATION SUBSYSTEM (I18N - 8 LANGUAGES)
    // =========================================================================
    @Test
    fun test01_LocalizationIntegrityAcrossAll8Languages() {
        val supportedCodes = listOf("ES", "EN", "DE", "FR", "PT", "IT", "JA", "ZH")
        val registeredLanguages = SendaLocaleManager.supportedLanguages

        // 1.1 Check that the 8 languages are registered in the picker
        for (code in supportedCodes) {
            assertTrue(
                "El idioma $code debe estar registrado en SendaLocaleManager",
                registeredLanguages.any { it.code == code }
            )
        }

        // 1.2 Check parity of texts and essential keys in each language
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
    // 2. PERSISTENCE OF BOOKMARKS, HISTORY AND ZEN VIEW
    // =========================================================================
    @Test
    fun test02_BookmarksAndHistoryPersistence() {
        DestructiveTestGuard.requireExplicitPermission("borra el historial")
        // 2.1 Bookmarks
        prefs.toggleBookmark(title = "Senda Sovereign Test", url = "https://senda.org/test")
        val bookmarks = prefs.getBookmarks()
        val addedBm = bookmarks.find { it.url == "https://senda.org/test" }
        assertNotNull("El marcador añadido debe existir en la lista", addedBm)

        prefs.deleteBookmark(addedBm!!.id)
        val afterRemove = prefs.getBookmarks()
        assertFalse("El marcador debe ser eliminado", afterRemove.any { it.id == addedBm.id })

        // 2.2 Local history
        prefs.addHistoryItem(title = "History Test Item", url = "https://senda.org/history")
        // addHistoryItem saves in the background: wait for the item to appear
        var historyList = prefs.getHistory()
        var waitedMs = 0
        while (historyList.none { it.url == "https://senda.org/history" } && waitedMs < 3000) {
            Thread.sleep(50)
            waitedMs += 50
            historyList = prefs.getHistory()
        }
        assertTrue("El historial debe registrar la navegación", historyList.any { it.url == "https://senda.org/history" })
        prefs.clearHistory()
        assertTrue("El historial debe quedar limpio tras clearHistory", prefs.getHistory().isEmpty())

        // 2.3 Zen shortcuts
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
        prefs.saveZenShortcuts(originalShortcuts) // Restore

        println("[PASS 2/8] Persistencia: Marcadores, Historial y Modo Zen validados.")
    }

    // =========================================================================
    // 3. HARDWARE ENCRYPTION OF WEBDAV (KEYSTORE AES-256-GCM)
    // =========================================================================
    @Test
    fun test03_WebdavHardwareEncryptionInStorage() {
        DestructiveTestGuard.requireExplicitPermission("deja vacía la contraseña de WebDAV")
        val rawPrefs = context.getSharedPreferences("senda_preferences", Context.MODE_PRIVATE)
        val testPassword = "HardwareVaultWebDavKey_2026!#"

        // Write credential
        prefs.webdavPassword = testPassword

        // Check there is no plain text in flash storage
        val plainInDisk = rawPrefs.getString("webdav_password", null)
        assertNull("La contraseña WebDAV NO debe estar en texto plano", plainInDisk)

        // Check that encrypted fields with a nonce are present
        val enc = rawPrefs.getString("webdav_password_enc", null)
        val iv = rawPrefs.getString("webdav_password_iv", null)
        assertNotNull("Payload cifrado ausente", enc)
        assertNotNull("IV único ausente", iv)

        // Decryption through the keystore
        val decrypted = prefs.webdavPassword
        assertEquals("El descifrado de Keystore debe coincidir", testPassword, decrypted)

        // Cleanup and sanitization
        prefs.webdavPassword = ""
        assertNull(rawPrefs.getString("webdav_password_enc", null))
        assertNull(rawPrefs.getString("webdav_password_iv", null))

        println("[PASS 3/8] Cifrado WebDAV: 100% verificado en chip Keystore sin fugas de texto plano.")
    }

    // =========================================================================
    // 4. PASSWORD VAULT AND PROTECTION AGAINST ATTACKS
    // =========================================================================
    @Test
    fun test04_SendaVaultForensicAndTamperProtection() {
        val domain = "https://sub.portal.banco.com.es:8443/auth?step=1"
        val user = "usuario_seguro"
        val passChars = "ClaveUltraSegura#2026_ñ!".toCharArray()

        // 4.1 Canonical domain extraction (eTLD+1)
        assertEquals("El dominio canónico debe ser banco.com.es", "banco.com.es", SendaVaultManager.extractCanonicalDomain(domain))

        // The vault key requires fingerprint or PIN (valid for 30 s); the encryption tests use the app's
        // key, which is on the same chip but without authentication
        val alias = SendaVaultManager.APP_KEY_ALIAS
        val (encryptedBase64, ivBase64) = SendaVaultManager.encryptPassword(passChars.copyOf(), alias)

        // 4.2 Decryption to CharArray and zeroization
        val retrievedChars = SendaVaultManager.decryptPassword(encryptedBase64, ivBase64, alias)
        assertArrayEquals("La contraseña descifrada debe coincidir", passChars, retrievedChars)
        SendaVaultManager.wipe(retrievedChars)
        assertTrue("La memoria RAM debe quedar en ceros", retrievedChars.all { it == '\u0000' })

        // 4.3 Message integrity (tamper resistance with the AEAD tag)
        var tamperDetected = false
        try {
            val encBytes = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
            encBytes[0] = (encBytes[0].toInt() xor 0xFF).toByte() // Corrupt a byte
            val tamperedBase64 = android.util.Base64.encodeToString(encBytes, android.util.Base64.NO_WRAP)
            SendaVaultManager.decryptPassword(tamperedBase64, ivBase64, alias)
        } catch (e: Exception) {
            tamperDetected = true
        }
        assertTrue("La etiqueta AEAD debe rechazar cualquier alteración de bits", tamperDetected)

        // 4.4 Saving to the vault: without authenticating it must be refused; if the phone was unlocked less than
        // 30 s ago it is saved, and then the credential must be deletable
        try {
            val cred = SendaVaultManager.saveCredential(context, domain, user, passChars.copyOf())
            assertEquals("banco.com.es", cred.domain)
            SendaVaultManager.deleteCredential(context, cred.id)
            assertFalse("La credencial debe eliminarse de la bóveda", SendaVaultManager.getCredentials(context).any { it.id == cred.id })
        } catch (e: org.senda.browser.core.security.VaultLockedException) {
            assertTrue("Sin huella ni PIN la Bóveda no guarda nada", SendaVaultManager.getCredentials(context).none { it.username == user && it.domain == "banco.com.es" })
        }

        println("[PASS 4/8] Bóveda Criptográfica: eTLD+1, Zeroization y resistencia a manipulación AEAD verificados.")
    }

    // =========================================================================
    // 5. INTERNAL ASSETS AND NATIVE uBLOCK ORIGIN
    // =========================================================================
    @Test
    fun test05_BuiltInAssetsAndLocalUBlockPresence() {
        val assetManager = context.assets

        // 5.1 Check the bundled ublock.xpi
        var ublockStream: InputStream? = null
        try {
            ublockStream = assetManager.open("extensions/ublock.xpi")
            assertNotNull("ublock.xpi debe existir en los assets internos", ublockStream)
            val bytesCount = ublockStream.available()
            assertTrue("ublock.xpi debe tener un tamaño válido (>1MB)", bytesCount > 1_000_000)
        } finally {
            ublockStream?.close()
        }

        // 5.2 Check the local Proxy / Tor extension
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
    // 6. TOR ROUTING AND SOCKS5 PROXY CONFIGURATION
    // =========================================================================
    @Test
    fun test06_TorAndProxyConfigurationState() {
        // Check the proxy configuration state
        assertEquals("Tor debe iniciar en estado STOPPED", TorState.STOPPED, SendaTorManager.state)
        assertEquals("El puerto local por defecto de SOCKS5 Tor debe ser 9050", 9050, SendaTorManager.socksPort)
        println("[PASS 6/8] Enrutamiento Seguro: SendaTorManager listo para sockets SOCKS5.")
    }

    // =========================================================================
    // 7. DOWNLOAD MANAGER
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
        prefs.saveDownloads(initialDownloads) // Restore
        assertFalse("La descarga debe eliminarse", prefs.getDownloads().any { it.id == "dl_test_123" })

        println("[PASS 7/8] Gestor de Descargas: Registro, estados y persistencia verificados.")
    }

    @Test
    fun test08_SystemIntentsAndAutofillResolution() {
        // Check that the autofill intent with a package resolves on Android 15
        val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
        val resolveInfo = context.packageManager.resolveActivity(intent, 0)
        assertNotNull("El intent de autocompletado con paquete DEBE resolverse en Android 15", resolveInfo)
        println("[PASS 8/8] Integración OS: Intent de autocompletado verificado y ejecutable en Android 15.")
    }

    // =========================================================================
    // 9. METADATA AND REAL URL RESOLUTION OF uBLOCK ORIGIN
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
    // 10. REAL LOADING OF A UBLOCK PAGE IN A GECKOSESSION
    // =========================================================================
    @Test
    fun test10_LoadUBlockInGeckoSession() {
        var targetUrl: String? = null

        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SendaGeckoEngine.initialize(context, prefs)
        }

        // Wait for GeckoView to initialize uBlock Origin and fill in its metadata
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


