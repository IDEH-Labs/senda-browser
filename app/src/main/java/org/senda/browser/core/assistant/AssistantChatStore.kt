package org.senda.browser.core.assistant

import android.content.Context
import androidx.core.util.AtomicFile
import org.json.JSONObject
import org.senda.browser.core.security.SendaVaultManager
import java.io.File
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The assistant conversation saved on the phone, encrypted, until the user deletes it.
 *
 * - The conversation is encrypted with its own random AES-256 key (AES-GCM).
 * - That key is stored wrapped by [SendaVaultManager.ASSISTANT_CHAT_KEY_ALIAS]: a key in secure hardware that
 *   the chip only uses after a recent fingerprint or PIN. Without authenticating, the file cannot be read.
 * - The unwrapped key is only in memory while the chat is unlocked ([unlock] … [lock]), so a reply that arrives
 *   after the 30 s of the fingerprint can still be saved.
 * - The file is excluded from backups (allowBackup=false) and is only deleted when the user asks ([delete]).
 */
object AssistantChatStore {
    private const val FILE_NAME = "assistant_chat.enc"
    private const val FORMAT_VERSION = 1
    private const val GCM_TAG_LENGTH_BITS = 128

    private val random = SecureRandom()

    /** Wraps the conversation key with a key in secure hardware. Replaceable only in tests. */
    internal interface KeyWrapper {
        fun wrap(key: ByteArray): Pair<ByteArray, ByteArray>
        fun unwrap(wrapped: ByteArray, iv: ByteArray): ByteArray
        fun reset()
    }

    internal var wrapper: KeyWrapper = object : KeyWrapper {
        override fun wrap(key: ByteArray) = SendaVaultManager.encryptBytes(key, SendaVaultManager.ASSISTANT_CHAT_KEY_ALIAS)
        override fun unwrap(wrapped: ByteArray, iv: ByteArray) =
            SendaVaultManager.decryptBytes(wrapped, iv, SendaVaultManager.ASSISTANT_CHAT_KEY_ALIAS)
        override fun reset() = SendaVaultManager.deleteKey(SendaVaultManager.ASSISTANT_CHAT_KEY_ALIAS)
    }

    // Present only while unlocked
    private var dataKey: ByteArray? = null
    private var wrappedKey: ByteArray? = null
    private var wrapIv: ByteArray? = null

    private fun file(dir: File) = File(dir, FILE_NAME)
    private fun dir(context: Context) = context.applicationContext.filesDir

    fun exists(context: Context): Boolean = file(dir(context)).exists()

    @get:Synchronized
    val isUnlocked: Boolean get() = dataKey != null

    /**
     * Must be called right after a successful fingerprint/PIN. Returns the saved conversation (JSON), or null if
     * there is none yet. Throws [org.senda.browser.core.security.VaultUnavailableException] (no screen lock,
     * no secure hardware or key invalidated) or [org.senda.browser.core.security.VaultLockedException].
     */
    fun unlock(context: Context): String? = unlock(dir(context))

    @Synchronized
    internal fun unlock(dir: File): String? {
        val f = file(dir)
        if (!f.exists()) {
            // First use: a new conversation key, wrapped by the hardware key now that the user just authenticated
            val key = ByteArray(32).also { random.nextBytes(it) }
            val (wrapped, iv) = wrapper.wrap(key)
            dataKey = key; wrappedKey = wrapped; wrapIv = iv
            return null
        }
        val json = JSONObject(AtomicFile(f).readFully().toString(Charsets.UTF_8))
        val wrapped = b64(json.getString("wrappedKey"))
        val iv = b64(json.getString("wrapIv"))
        val key = wrapper.unwrap(wrapped, iv)
        val plain = aes(Cipher.DECRYPT_MODE, key, b64(json.getString("iv")), b64(json.getString("data")))
        dataKey = key; wrappedKey = wrapped; wrapIv = iv
        return try { plain.toString(Charsets.UTF_8) } finally { Arrays.fill(plain, 0) }
    }

    /** Saves the conversation (JSON). Does nothing if the chat is locked. */
    fun save(context: Context, conversation: String) = save(dir(context), conversation)

    @Synchronized
    internal fun save(dir: File, conversation: String) {
        val key = dataKey ?: return
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val plain = conversation.toByteArray(Charsets.UTF_8)
        val encrypted = try { aes(Cipher.ENCRYPT_MODE, key, iv, plain) } finally { Arrays.fill(plain, 0) }
        val json = JSONObject()
            .put("v", FORMAT_VERSION)
            .put("wrappedKey", s64(wrappedKey!!))
            .put("wrapIv", s64(wrapIv!!))
            .put("iv", s64(iv))
            .put("data", s64(encrypted))
        val atomic = AtomicFile(file(dir))
        val out = atomic.startWrite()
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    /** Forgets the key from memory: the next opening asks for fingerprint or PIN again. */
    @Synchronized
    fun lock() {
        dataKey?.let { Arrays.fill(it, 0) }
        dataKey = null; wrappedKey = null; wrapIv = null
    }

    /** Deletes the saved conversation. If the chat is unlocked it stays unlocked, ready for a new one. */
    fun delete(context: Context) = delete(dir(context))

    @Synchronized
    internal fun delete(dir: File) {
        AtomicFile(file(dir)).delete()
    }

    /**
     * The screen lock was removed or reset and Android invalidated the key: the saved conversation can no
     * longer be decrypted. Deletes it and the key so a new one can be created.
     */
    fun resetInvalidated(context: Context) = resetInvalidated(dir(context))

    @Synchronized
    internal fun resetInvalidated(dir: File) {
        lock()
        AtomicFile(file(dir)).delete()
        wrapper.reset()
    }

    private fun aes(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            doFinal(input)
        }

    private fun b64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
    private fun s64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
}
