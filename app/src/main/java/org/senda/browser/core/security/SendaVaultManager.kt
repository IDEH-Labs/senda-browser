package org.senda.browser.core.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import androidx.core.util.AtomicFile
import org.senda.browser.core.SendaGeckoEngine

/**
 * Immutable model of a credential stored in Senda's vault.
 * The password is stored encrypted with AES-256-GCM and its own unique nonce/IV.
 */
data class VaultCredential(
    val id: String = UUID.randomUUID().toString(),
    val domain: String,             // Verified canonical domain (eTLD+1)
    val originUrl: String,          // Full origin URL
    val username: String,           // Username or email
    val encryptedPasswordBase64: String, // Ciphertext in Base64
    val ivBase64: String,           // Unique 12-byte nonce/IV in Base64
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val notes: String = ""
)

/**
 * Cryptographic manager of Senda's password vault. Every point can be checked in this file:
 * - Non-exportable AES-256 key, generated and used only inside the phone's secure hardware
 *   (StrongBox if present, otherwise the TEE). Without secure hardware, the vault does not open (NO_SECURE_HARDWARE).
 * - The key is only used after a fingerprint or PIN within the last 30 s and with the phone unlocked.
 * - Authenticated AES-256-GCM encryption (random 96-bit IV per password, 128-bit tag).
 *   AES-256 is the symmetric algorithm the NSA requires in CNSA 2.0 for classified information.
 * - Passwords in CharArray/ByteArray that are overwritten after use (best effort: the JVM may
 *   have made copies we do not control).
 * - Canonical domains (eTLD+1) with Mozilla's Public Suffix List.
 * - Atomic saving with AtomicFile (temporary file, fsync and rename).
 * - Clipboard marked as sensitive (Android 13+) and cleared after 30 s.
 *
 * Designed following the OWASP MASVS-CRYPTO, MASVS-AUTH and MASVS-STORAGE controls.
 * It has not had an external audit: compliance with any MASVS profile is not claimed.
 */
object SendaVaultManager {

    private const val TAG = "SendaVault"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    /** Key for the vault passwords: requires a recent fingerprint or PIN and the phone unlocked. */
    const val VAULT_KEY_ALIAS = "senda_vault_master_key_v2"
    /**
     * Key for secrets Senda uses without asking and for the audit
     * tests. It is the original key (v1): on the chip, but without requiring authentication.
     */
    const val APP_KEY_ALIAS = "senda_vault_master_key_v1"
    /** Keys that only work in secure hardware and after a recent fingerprint or PIN. */
    private val AUTH_KEY_ALIASES = setOf(VAULT_KEY_ALIAS)
    /** Seconds the key stays available after authenticating with fingerprint or PIN. */
    const val AUTH_VALIDITY_SECONDS = 30
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val VAULT_FILE_NAME = "senda_vault_store.json"

    private val secureRandom = SecureRandom()
    private val scope = CoroutineScope(Dispatchers.Default)

    /** Aliases whose key was already verified to live in secure hardware (avoids repeating the query on every use). */
    private val verifiedHardwareAliases = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Real level of the key according to the keystore: "STRONGBOX", "TEE", "SOFTWARE" or "DESCONOCIDO" (unknown). */
    private fun securityLevelOf(key: SecretKey): String {
        val keyInfo = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
                else -> "DESCONOCIDO"
            }
        } else {
            @Suppress("DEPRECATION")
            if (keyInfo.isInsideSecureHardware) "TEE" else "SOFTWARE"
        }
    }

    /**
     * Passwords are only encrypted with a key that lives in secure hardware (StrongBox or TEE).
     * If Android only offers a software key, or where it lives cannot be confirmed, the vault is not used.
     * A newly created key that turns out to be in software is deleted; an existing one is not touched.
     */
    private fun requireSecureHardware(key: SecretKey, alias: String, justGenerated: Boolean): SecretKey {
        if (alias in verifiedHardwareAliases) return key
        val level = try { securityLevelOf(key) } catch (e: Exception) {
            Log.w(TAG, "No se pudo confirmar el nivel de seguridad de la clave: ${e.message}")
            "DESCONOCIDO"
        }
        if (level == "STRONGBOX" || level == "TEE") {
            verifiedHardwareAliases.add(alias)
            return key
        }
        Log.w(TAG, "Clave de la Bóveda fuera de hardware seguro ($level): la Bóveda no se usa")
        if (justGenerated) {
            try {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
            } catch (_: Exception) { }
        }
        throw VaultUnavailableException(VaultUnavailableException.Reason.NO_SECURE_HARDWARE)
    }

    /**
     * Gets or generates the AES-256 master key in the Android Keystore. For the vault it also requires
     * the key to live in secure hardware (StrongBox or TEE).
     */
    @Synchronized
    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return if (alias in AUTH_KEY_ALIASES) requireSecureHardware(entry.secretKey, alias, justGenerated = false)
                else entry.secretKey
            }
        }
        val generated = generateKey(alias)
        return if (alias in AUTH_KEY_ALIASES) requireSecureHardware(generated, alias, justGenerated = true) else generated
    }

    private fun generateKey(alias: String): SecretKey {

        // Generate a new hardware-backed AES-256 key
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
            if (alias in AUTH_KEY_ALIASES) {
                // The chip only uses the key if the user authenticated (fingerprint or PIN) less than 30 s ago
                builder.setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                }
                // Note: with a 30 s validity Android ignores this option (it only applies to keys without validity, used
                // once with a fingerprint). What does invalidate the key is removing or resetting the screen lock
                builder.setInvalidatedByBiometricEnrollment(true)
                // Unusable while the screen is locked
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setUnlockedDeviceRequired(true)
                }
            }
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }
            return builder.build()
        }

        // If the hardware supports StrongBox (dedicated security module), try to use it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                keyGenerator.init(spec(strongBox = true))
                return keyGenerator.generateKey()
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox no disponible, recurriendo a TEE estándar: ${e.message}")
            }
        }

        return try {
            keyGenerator.init(spec(strongBox = false))
            keyGenerator.generateKey()
        } catch (e: Exception) {
            // Without a PIN, pattern or password on the phone, a key that requires authentication cannot be created
            if (alias in AUTH_KEY_ALIASES) throw VaultUnavailableException(VaultUnavailableException.Reason.NO_SCREEN_LOCK, e)
            throw e
        }
    }

    private fun cipherFor(mode: Int, alias: String, iv: ByteArray? = null): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            val key = getOrCreateKey(alias)
            if (iv == null) cipher.init(mode, key) else cipher.init(mode, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            throw VaultLockedException(e)
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            throw VaultUnavailableException(VaultUnavailableException.Reason.KEY_INVALIDATED, e)
        }
        return cipher
    }

    /**
     * Did Android invalidate the vault key? It happens when the screen lock is removed or reset. Passwords
     * encrypted with it can no longer be decrypted. Does not create the key if it does not exist yet.
     */
    fun isVaultKeyInvalidated(): Boolean = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(VAULT_KEY_ALIAS)) {
            false
        } else {
            val key = (keyStore.getEntry(VAULT_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (key == null) false else {
                Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
                false
            }
        }
    } catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) {
        true
    } catch (_: Exception) {
        // Locked (no recent fingerprint or PIN) or another reason: it is not invalidated
        false
    }

    /** Deletes the invalidated key and the passwords that can no longer be decrypted, to start a new vault. */
    fun resetInvalidatedVaultKey(context: Context) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(VAULT_KEY_ALIAS)
        verifiedHardwareAliases.remove(VAULT_KEY_ALIAS)
        saveAll(context, emptyList())
    }

    /** Deletes a keystore key (e.g. an invalidated one) so the next use creates and verifies a new one. */
    fun deleteKey(alias: String) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
        verifiedHardwareAliases.remove(alias)
    }

    /** Does the vault key require user authentication? (reported by the keystore itself) */
    fun vaultKeyRequiresAuth(): Boolean = try {
        val key = getOrCreateKey(VAULT_KEY_ALIAS)
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        info.isUserAuthenticationRequired
    } catch (_: Exception) {
        false
    }

    /**
     * Where the master key really lives, according to the keystore itself: "STRONGBOX", "TEE", "SOFTWARE"
     * or "DESCONOCIDO" (unknown) if Android does not report it. If the vault rejects the key for being in software, it says so.
     */
    fun keySecurityLevel(alias: String = VAULT_KEY_ALIAS): String {
        return try {
            securityLevelOf(getOrCreateKey(alias))
        } catch (e: VaultUnavailableException) {
            if (e.reason == VaultUnavailableException.Reason.NO_SECURE_HARDWARE) "SOFTWARE" else "DESCONOCIDO"
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar el nivel de seguridad de la clave: ${e.message}")
            "DESCONOCIDO"
        }
    }

    fun isHardwareBacked(): Boolean {
        return try {
            val level = securityLevelOf(getOrCreateKey(VAULT_KEY_ALIAS))
            level == "STRONGBOX" || level == "TEE"
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar el estado de hardware seguro: ${e.message}")
            false // If it cannot be checked, it is not claimed to be in hardware
        }
    }

    /**
     * Encrypts a password in live RAM using AES-256-GCM.
     * Right after encrypting, the input buffer is wiped (zeroization).
     */
    @Synchronized
    fun encryptPassword(passwordChars: CharArray, alias: String = VAULT_KEY_ALIAS): Pair<String, String> {
        // The hardware keystore always generates the random IV in ENCRYPT_MODE
        val cipher = cipherFor(Cipher.ENCRYPT_MODE, alias)
        val iv = cipher.iv

        // Temporarily convert to bytes for the Cipher and wipe it immediately
        val byteBuffer = java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(passwordChars))
        val passwordBytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(passwordBytes)

        val encryptedBytes = cipher.doFinal(passwordBytes)

        // ACTIVE ZEROIZATION: overwrite bytes in RAM
        Arrays.fill(passwordBytes, 0.toByte())

        val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)

        return Pair(encryptedBase64, ivBase64)
    }

    /**
     * Decrypts a credential directly into a mutable character array (`CharArray`).
     * Never returns an immutable `String`, to prevent leaks through the garbage collector.
     */
    @Synchronized
    fun decryptPassword(encryptedBase64: String, ivBase64: String, alias: String = VAULT_KEY_ALIAS): CharArray {
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)

        val decryptedBytes = try {
            cipherFor(Cipher.DECRYPT_MODE, alias, iv).doFinal(encryptedBytes)
        } catch (e: javax.crypto.AEADBadTagException) {
            // Passwords saved before the v2 key (encrypted with the original key): that key must also
            // live in secure hardware for the vault to use it
            if (alias != VAULT_KEY_ALIAS) throw e
            val legacyKey = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .let { (it.getEntry(APP_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey } ?: throw e
            requireSecureHardware(legacyKey, APP_KEY_ALIAS, justGenerated = false)
            cipherFor(Cipher.DECRYPT_MODE, APP_KEY_ALIAS, iv).doFinal(encryptedBytes)
        }

        // Decode to CharArray
        val charBuffer = java.nio.charset.StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(decryptedBytes))
        val resultChars = CharArray(charBuffer.remaining())
        charBuffer.get(resultChars)

        // ZEROIZATION of the intermediate byte buffer
        Arrays.fill(decryptedBytes, 0.toByte())

        return resultChars
    }

    /** Encrypts bytes with AES-256-GCM using a keystore key; returns (ciphertext, IV). Does not wipe the input. */
    @Synchronized
    fun encryptBytes(plain: ByteArray, alias: String): Pair<ByteArray, ByteArray> {
        val cipher = cipherFor(Cipher.ENCRYPT_MODE, alias)
        return Pair(cipher.doFinal(plain), cipher.iv)
    }

    /** Decrypts what [encryptBytes] produced with the same keystore key. */
    @Synchronized
    fun decryptBytes(encrypted: ByteArray, iv: ByteArray, alias: String): ByteArray =
        cipherFor(Cipher.DECRYPT_MODE, alias, iv).doFinal(encrypted)

    /**
     * Memory hygiene: actively wipes confidential characters.
     */
    fun wipe(chars: CharArray) {
        Arrays.fill(chars, '\u0000')
    }

    /**
     * Memory hygiene: actively wipes confidential bytes.
     */
    fun wipe(bytes: ByteArray) {
        Arrays.fill(bytes, 0.toByte())
    }

    // =========================================================================
    // ANTI-PHISHING AND DOMAIN CANONICALIZATION (eTLD+1)
    // =========================================================================

    /**
     * Extracts the strict canonical root domain of a URL to prevent subdomain attacks.
     * Example: "https://login.banco.com.es:8443/auth" -> "banco.com.es"
     * Fake example: "https://paypal.com.evil-phish.net" -> "evil-phish.net" (detects the mismatch)
     */
    fun extractCanonicalDomain(rawUrl: String, context: Context? = null): String {
        if (rawUrl.isBlank()) return ""
        return try {
            var clean = rawUrl.trim().lowercase()
            if (clean.startsWith("http://")) clean = clean.substring(7)
            if (clean.startsWith("https://")) clean = clean.substring(8)

            // Remove path, query and fragment first: an "@" there (e.g. ?email=ana@example.com) is not part of the host
            val endIdx = clean.indexOfAny(charArrayOf('/', '?', '#'))
            if (endIdx != -1) clean = clean.substring(0, endIdx)

            // Remove user:pass@ credentials
            val atIdx = clean.lastIndexOf('@')
            if (atIdx != -1) clean = clean.substring(atIdx + 1)

            // Remove port :8080
            val colonIdx = clean.indexOf(':')
            if (colonIdx != -1) clean = clean.substring(0, colonIdx)

            val effectiveCtx = context ?: SendaGeckoEngine.appContext
            PublicSuffixList.getRegistrableDomain(clean, effectiveCtx)
        } catch (e: Exception) {
            rawUrl
        }
    }

    /**
     * Checks whether a current URL matches exactly the domain of the stored credential.
     */
    fun matchesDomain(currentUrl: String, credentialDomain: String): Boolean {
        val currentDomain = extractCanonicalDomain(currentUrl)
        val targetDomain = extractCanonicalDomain(credentialDomain)
        return currentDomain.isNotBlank() && targetDomain.isNotBlank() && currentDomain.equals(targetDomain, ignoreCase = true)
    }

    // =========================================================================
    // VAULT PERSISTENCE AND STORAGE
    // =========================================================================

    /**
     * Loads all the credentials saved in the local vault.
     */
    fun getCredentials(context: Context): List<VaultCredential> {
        val file = File(context.filesDir, VAULT_FILE_NAME)
        val bakFile = File(context.filesDir, "$VAULT_FILE_NAME.bak")
        if (!file.exists() && !bakFile.exists()) return emptyList()

        return try {
            val atomicFile = AtomicFile(file)
            val bytes = atomicFile.readFully()
            val content = String(bytes, Charsets.UTF_8)
            val array = JSONArray(content)
            val list = mutableListOf<VaultCredential>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    VaultCredential(
                        id = obj.getString("id"),
                        domain = obj.getString("domain"),
                        originUrl = obj.optString("originUrl", ""),
                        username = obj.getString("username"),
                        encryptedPasswordBase64 = obj.getString("encPass"),
                        ivBase64 = obj.getString("iv"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                        notes = obj.optString("notes", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo bóveda de contraseñas: ${e.message}")
            emptyList()
        }
    }

    /**
     * Saves a credential in the vault (adds it, or updates it if one already exists for that domain and user).
     */
    fun saveCredential(
        context: Context,
        domainOrUrl: String,
        username: String,
        passwordChars: CharArray,
        notes: String = ""
    ): VaultCredential {
        val canonicalDomain = extractCanonicalDomain(domainOrUrl)
        val (encryptedPass, iv) = encryptPassword(passwordChars)

        val currentList = getCredentials(context).toMutableList()
        val existingIndex = currentList.indexOfFirst {
            it.domain.equals(canonicalDomain, ignoreCase = true) && it.username == username
        }

        val credential = if (existingIndex >= 0) {
            val existing = currentList[existingIndex]
            existing.copy(
                encryptedPasswordBase64 = encryptedPass,
                ivBase64 = iv,
                updatedAt = System.currentTimeMillis(),
                notes = notes.ifBlank { existing.notes }
            ).also { currentList[existingIndex] = it }
        } else {
            VaultCredential(
                domain = canonicalDomain,
                originUrl = domainOrUrl,
                username = username,
                encryptedPasswordBase64 = encryptedPass,
                ivBase64 = iv,
                notes = notes
            ).also { currentList.add(0, it) }
        }

        saveAll(context, currentList)
        return credential
    }

    /** What an import did: new accounts, accounts replaced with the file's password, accounts kept as they were. */
    data class ImportSummary(val added: Int, val replaced: Int, val kept: Int)

    /**
     * Saves many logins at once (CSV import). An account that already exists (same site and username) keeps
     * Senda's password unless [replaceExisting]. Everything is encrypted first and written in a single atomic
     * save: if the key is locked half-way ([VaultLockedException]) nothing has been changed.
     */
    @Synchronized
    fun importLogins(context: Context, logins: List<VaultCsv.Login>, replaceExisting: Boolean): ImportSummary {
        val list = getCredentials(context).toMutableList()
        var added = 0; var replaced = 0; var kept = 0
        for (login in logins) {
            val domain = extractCanonicalDomain(login.url, context)
            if (domain.isBlank()) continue
            val index = list.indexOfFirst { it.domain.equals(domain, ignoreCase = true) && it.username == login.username }
            if (index >= 0 && !replaceExisting) { kept++; continue }
            val chars = login.password.toCharArray()
            val (enc, iv) = try { encryptPassword(chars) } finally { wipe(chars) }
            if (index >= 0) {
                list[index] = list[index].copy(encryptedPasswordBase64 = enc, ivBase64 = iv, updatedAt = System.currentTimeMillis(),
                    notes = login.note.ifBlank { list[index].notes })
                replaced++
            } else {
                list.add(VaultCredential(domain = domain, originUrl = login.url, username = login.username,
                    encryptedPasswordBase64 = enc, ivBase64 = iv, notes = login.note))
                added++
            }
        }
        writeAll(context, list)
        return ImportSummary(added, replaced, kept)
    }

    /** Decrypts every saved password for an export the user asked for. Requires a recent fingerprint or PIN. */
    @Synchronized
    fun exportLogins(context: Context): List<VaultCsv.Login> = getCredentials(context).map { c ->
        val chars = decryptPassword(c.encryptedPasswordBase64, c.ivBase64)
        val password = try { String(chars) } finally { wipe(chars) }
        val url = c.originUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: "https://${c.domain}"
        VaultCsv.Login(url, c.username, password, c.notes)
    }

    /**
     * Edits an existing credential (site, username and password). Returns false if there is already another account
     * with the same site and username, so two identical entries are not left.
     */
    fun updateCredential(
        context: Context,
        credentialId: String,
        domainOrUrl: String,
        username: String,
        passwordChars: CharArray
    ): Boolean {
        val currentList = getCredentials(context).toMutableList()
        val index = currentList.indexOfFirst { it.id == credentialId }
        if (index < 0) return false
        val canonicalDomain = extractCanonicalDomain(domainOrUrl)
        if (currentList.any { it.id != credentialId && it.domain.equals(canonicalDomain, ignoreCase = true) && it.username == username }) {
            return false
        }
        val (encryptedPass, iv) = encryptPassword(passwordChars)
        val existing = currentList[index]
        currentList[index] = existing.copy(
            domain = canonicalDomain,
            originUrl = if (canonicalDomain.equals(existing.domain, ignoreCase = true)) existing.originUrl else domainOrUrl,
            username = username,
            encryptedPasswordBase64 = encryptedPass,
            ivBase64 = iv,
            updatedAt = System.currentTimeMillis()
        )
        saveAll(context, currentList)
        return true
    }

    /**
     * Deletes a credential by its unique identifier.
     */
    fun deleteCredential(context: Context, credentialId: String) {
        val currentList = getCredentials(context).filter { it.id != credentialId }
        saveAll(context, currentList)
    }

    private fun saveAll(context: Context, list: List<VaultCredential>) {
        try {
            writeAll(context, list)
        } catch (e: Exception) {
            Log.e(TAG, "Error persistiendo bóveda: ${e.message}")
        }
    }

    /** Like [saveAll], but a failed write is reported to the caller instead of only logged. */
    private fun writeAll(context: Context, list: List<VaultCredential>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("domain", item.domain)
                put("originUrl", item.originUrl)
                put("username", item.username)
                put("encPass", item.encryptedPasswordBase64)
                put("iv", item.ivBase64)
                put("createdAt", item.createdAt)
                put("updatedAt", item.updatedAt)
                put("notes", item.notes)
            }
            array.put(obj)
        }
        val atomicFile = AtomicFile(File(context.filesDir, VAULT_FILE_NAME))
        val fos = atomicFile.startWrite()
        try {
            fos.write(array.toString(2).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(fos)
        } catch (e: Exception) {
            atomicFile.failWrite(fos)
            throw e
        }
    }

    // =========================================================================
    // CLIPBOARD HYGIENE (ANTI-SNOOPING & AUTO-CLEAR AFTER 30 s)
    // =========================================================================

    /**
     * Copies a password to the clipboard with the sensitive-content flag (Android 13+)
     * and starts a timer that clears it after 30 seconds.
     */
    fun copyToClipboardSecurely(context: Context, label: String, passwordChars: CharArray) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val passString = String(passwordChars)
        val clip = ClipData.newPlainText(label, passString)

        // Tell Android 13+ the content is confidential (hides the visual preview)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }

        clipboard.setPrimaryClip(clip)

        // Schedule clearing in 30 seconds
        scope.launch {
            delay(30_000L)
            try {
                // Since Android 10 a background app cannot read the clipboard (it returns null).
                // In that case it is cleared anyway: losing a later copy is better than leaving the password
                val currentClip = clipboard.primaryClip
                val stillOurs = currentClip == null ||
                    (currentClip.itemCount > 0 && currentClip.getItemAt(0)?.text?.toString() == passString)
                if (stillOurs) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    Log.i(TAG, "Portapapeles confidencial purgado tras 30 s")
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo purgar el portapapeles: ${e.message}")
            }
        }
    }

    // =========================================================================
    // HIGH-ENTROPY PASSWORD GENERATOR
    // =========================================================================

    /**
     * Generates a cryptographically strong password with verifiable entropy.
     */
    fun generateStrongPassword(
        length: Int = 18,
        includeUpper: Boolean = true,
        includeLower: Boolean = true,
        includeDigits: Boolean = true,
        includeSymbols: Boolean = true
    ): Pair<CharArray, Double> {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ" // Without the confusing I, O
        val lower = "abcdefghijkmnopqrstuvwxyz" // Without the confusing l
        val digits = "23456789"                  // Without the confusing 0, 1
        val symbols = "!@#$%^&*()-_=+[]{}|;:,.<>?"

        val pool = StringBuilder()
        if (includeUpper) pool.append(upper)
        if (includeLower) pool.append(lower)
        if (includeDigits) pool.append(digits)
        if (includeSymbols) pool.append(symbols)

        val classes = listOfNotNull(
            upper.takeIf { includeUpper }, lower.takeIf { includeLower },
            digits.takeIf { includeDigits }, symbols.takeIf { includeSymbols }
        ).ifEmpty { listOf(lower, digits) }
        if (pool.isEmpty()) pool.append(lower).append(digits)

        // Purely at random, ~13 % of 20-character keys had no digit: one of each chosen class is guaranteed
        // and then everything is shuffled so those positions are not predictable
        val result = CharArray(length)
        for (i in 0 until length) {
            val source = if (i < classes.size) classes[i] else pool
            result[i] = source[secureRandom.nextInt(source.length)]
        }
        for (i in length - 1 downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val tmp = result[i]; result[i] = result[j]; result[j] = tmp
        }

        // Shannon entropy: E = L * log2(poolSize)
        val entropyBits = length * (Math.log(pool.length.toDouble()) / Math.log(2.0))

        return Pair(result, entropyBits)
    }
}

/** The vault key needs the user to authenticate (fingerprint or PIN) before it can be used. */
class VaultLockedException(cause: Throwable? = null) : Exception("Bóveda bloqueada: identifícate con huella o PIN", cause)

/** The vault cannot be used: there is no screen lock or the key was invalidated. */
class VaultUnavailableException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    /** NO_SECURE_HARDWARE: the phone cannot keep the key in StrongBox or a TEE. */
    enum class Reason { NO_SCREEN_LOCK, KEY_INVALIDATED, NO_SECURE_HARDWARE }
}
