package org.senda.browser.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import org.senda.browser.core.security.SendaVaultManager
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * Senda no longer includes an AI assistant (2026-10-10): any AI website works in a tab, so the built-in one only
 * added code and attack surface. What earlier versions stored is deleted at startup: API keys, the ChatGPT session,
 * the encrypted conversation and its key in secure hardware.
 *
 * The ChatGPT session is first revoked at OpenAI, so it does not stay valid on their side. Without network (or with
 * Tor still connecting) its encrypted tokens are kept, unusable by anything else, and revocation is retried at the
 * next startup.
 */
object RetiredAssistant {
    private const val TAG = "SendaRetiredAssistant"
    private const val TOKENS = "chatgpt_plan_tokens"
    private const val REVOKE = "https://auth.openai.com/api/accounts/oauth/revoke"
    private const val CHAT_KEY_ALIAS = "senda_assistant_chat_key_v1"
    private const val CHAT_FILE = "assistant_chat.enc"

    /** Runs off the main thread: revocation uses the network and may wait a few minutes for it. */
    fun cleanUp(context: Context) {
        val prefs = context.getSharedPreferences("senda_preferences", Context.MODE_PRIVATE)
        val isToken = { key: String -> key.endsWith("_$TOKENS") }
        // Everything except the ChatGPT session goes right away
        val stale = prefs.all.keys.filter { it.startsWith("assistant_") && !isToken(it) }
        // AtomicFile leaves ".bak" (and ".new" while writing) next to the file
        val chatFiles = listOf("", ".bak", ".new").map { File(context.filesDir, CHAT_FILE + it) }
        if (stale.isNotEmpty() || chatFiles.any { it.exists() }) {
            prefs.edit().apply { stale.forEach { remove(it) } }.apply()
            chatFiles.forEach { it.delete() }
            runCatching { SendaVaultManager.deleteKey(CHAT_KEY_ALIAS) }
            Log.i(TAG, "Datos del asistente retirado borrados")
        }
        // Tor may still be connecting right after startup: a few tries before leaving it for the next one
        if (!prefs.contains("assistant_key_enc_$TOKENS")) return
        repeat(6) { attempt ->
            if (attempt > 0) Thread.sleep(30_000)
            if (revokeChatGptSession(prefs)) {
                prefs.edit().apply { prefs.all.keys.filter(isToken).forEach { remove(it) } }.apply()
                return
            }
        }
    }

    /** True when there is nothing left to revoke: revoked, rejected by OpenAI or unreadable. False to retry later. */
    private fun revokeChatGptSession(prefs: SharedPreferences): Boolean {
        val session = try {
            val enc = prefs.getString("assistant_key_enc_$TOKENS", null) ?: return true
            val iv = prefs.getString("assistant_key_iv_$TOKENS", null) ?: return true
            val chars = SendaVaultManager.decryptPassword(enc, iv, SendaVaultManager.APP_KEY_ALIAS)
            JSONObject(String(chars)).also { SendaVaultManager.wipe(chars) }
        } catch (_: Exception) {
            return true
        }
        val token = session.optString("refresh").ifBlank { return true }
        val form = mapOf("token" to token, "token_type_hint" to "refresh_token", "client_id" to session.optString("clientId"))
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = SendaNet.open(REVOKE).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return try {
            conn.outputStream.use { it.write(form.toByteArray()) }
            // Any answer from OpenAI ends it: revoked, or already invalid
            Log.i(TAG, "Sesión de ChatGPT revocada (HTTP ${conn.responseCode})")
            true
        } catch (e: IOException) {
            Log.w(TAG, "Sin conexión para revocar la sesión de ChatGPT: se reintenta al próximo inicio")
            false
        } finally {
            conn.disconnect()
        }
    }
}
