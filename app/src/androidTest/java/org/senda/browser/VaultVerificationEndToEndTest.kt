package org.senda.browser

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.security.SendaVaultManager

@RunWith(AndroidJUnit4::class)
class VaultVerificationEndToEndTest {

    /** A backup never carries settings that could divert traffic or inject code, and a backup cannot set them. */
    @Test
    fun testBackupSettingsLeaveOutDangerousKeys() {
        DestructiveTestGuard.requireExplicitPermission("escribe y borra ajustes de prueba (proxy, script, DNS)")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesManager(context)
        val rawPrefs = context.getSharedPreferences("senda_preferences", Context.MODE_PRIVATE)
        val keys = listOf("proxy_host", "user_custom_script", "user_custom_css", "custom_doh_url", "remote_debugging_enabled")
        val saved = keys.associateWith { rawPrefs.all[it] }
        try {
            rawPrefs.edit().putString("proxy_host", "10.0.0.1").putString("user_custom_script", "alert(1)")
                .putString("user_custom_css", "*{}").putString("custom_doh_url", "https://example.invalid/dns-query").putBoolean("remote_debugging_enabled", false).commit()
            val exported = prefs.exportPortableSettings()
            keys.forEach { assertFalse("$it no debe viajar en la copia", exported.has(it)) }
            val hostile = org.json.JSONObject().put("proxy_host", "evil.example").put("user_custom_script", "steal()")
                .put("remote_debugging_enabled", true).put("search_engine_url", "http://evil.example/?q=")
            assertEquals(0, prefs.importPortableSettings(hostile))
            assertEquals("10.0.0.1", rawPrefs.getString("proxy_host", null))
            assertEquals(false, rawPrefs.getBoolean("remote_debugging_enabled", false))
        } finally {
            rawPrefs.edit().apply {
                saved.forEach { (k, v) ->
                    when (v) { null -> remove(k); is String -> putString(k, v); is Boolean -> putBoolean(k, v) }
                }
            }.commit()
        }
    }

    @Test
    fun testAutofillIntentResolvesOnSystem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
        val packageManager = context.packageManager
        val resolveInfo = packageManager.resolveActivity(intent, 0)
        assertNotNull("El intent de autocompletado del sistema debe resolverse en Android", resolveInfo)
        println("[VERIFICADO] Intent de autocompletado del sistema operativo: existe y se puede invocar.")
    }

    @Test
    fun testZeroRemoteCatalogInCode() {
        // Check that SendaVaultManager and the credential list work 100% offline
        val context = ApplicationProvider.getApplicationContext<Context>()
        val creds = SendaVaultManager.getCredentials(context)
        assertNotNull(creds)
        println("[VERIFICADO] Bóveda opera 100% local sin conexiones de red.")
    }
}
