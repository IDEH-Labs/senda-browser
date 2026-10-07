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
 * Modelo inmutable de credencial almacenada en la Bóveda de Senda.
 * La contraseña se almacena cifrada con AES-256-GCM y su propio Nonce/IV único.
 */
data class VaultCredential(
    val id: String = UUID.randomUUID().toString(),
    val domain: String,             // Dominio canónico verificado (eTLD+1)
    val originUrl: String,          // URL completa de origen
    val username: String,           // Nombre de usuario o correo
    val encryptedPasswordBase64: String, // Texto cifrado en Base64
    val ivBase64: String,           // Nonce/IV único de 12 bytes en Base64
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val notes: String = ""
)

/**
 * Gestor criptográfico de la Bóveda de Contraseñas de Senda. Cada punto es comprobable en este archivo:
 * - Clave AES-256 no exportable, generada y usada solo dentro del hardware seguro del teléfono
 *   (StrongBox si existe, si no el TEE). Sin hardware seguro, la Bóveda no se abre (NO_SECURE_HARDWARE).
 * - La clave solo se usa tras huella o PIN de los últimos 30 s y con el teléfono desbloqueado.
 * - Cifrado autenticado AES-256-GCM (IV aleatorio de 96 bits por contraseña, etiqueta de 128 bits).
 *   AES-256 es el algoritmo simétrico que exige la NSA en CNSA 2.0 para información clasificada.
 * - Contraseñas en CharArray/ByteArray que se sobrescriben tras su uso (mejor esfuerzo: la JVM puede
 *   haber hecho copias que no controlamos).
 * - Dominios canónicos (eTLD+1) con la Public Suffix List de Mozilla.
 * - Guardado atómico con AtomicFile (archivo temporal, fsync y renombrado).
 * - Portapapeles marcado como sensible (Android 13+) y borrado a los 30 s.
 *
 * Diseñada siguiendo los controles OWASP MASVS-CRYPTO, MASVS-AUTH y MASVS-STORAGE.
 * No ha tenido auditoría externa: no se afirma el cumplimiento de ningún perfil MASVS.
 */
object SendaVaultManager {

    private const val TAG = "SendaVault"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    /** Clave de las contraseñas de la Bóveda: exige huella o PIN reciente y el teléfono desbloqueado. */
    const val VAULT_KEY_ALIAS = "senda_vault_master_key_v2"
    /**
     * Clave para secretos que Senda usa sin preguntar (contraseña de WebDAV) y para las pruebas de la
     * auditoría. Es la clave original (v1): en el chip, pero sin exigir autenticación.
     */
    const val APP_KEY_ALIAS = "senda_vault_master_key_v1"
    /** Segundos que la clave queda disponible tras identificarse con huella o PIN. */
    const val AUTH_VALIDITY_SECONDS = 30
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val VAULT_FILE_NAME = "senda_vault_store.json"

    private val secureRandom = SecureRandom()
    private val scope = CoroutineScope(Dispatchers.Default)

    /** Alias cuya clave ya se comprobó que vive en hardware seguro (evita repetir la consulta en cada uso). */
    private val verifiedHardwareAliases = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Nivel real de la clave según el Keystore: "STRONGBOX", "TEE", "SOFTWARE" o "DESCONOCIDO". */
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
     * Las contraseñas solo se cifran con una clave que vive en hardware seguro (StrongBox o TEE).
     * Si Android solo ofrece una clave en software, o no se puede confirmar dónde vive, la Bóveda no se usa.
     * Una clave recién creada que resulta estar en software se borra; una ya existente no se toca.
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
     * Obtiene o genera la clave maestra AES-256 en el Android Keystore. Para la Bóveda, además, exige
     * que la clave viva en hardware seguro (StrongBox o TEE).
     */
    @Synchronized
    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return if (alias == VAULT_KEY_ALIAS) requireSecureHardware(entry.secretKey, alias, justGenerated = false)
                else entry.secretKey
            }
        }
        val generated = generateKey(alias)
        return if (alias == VAULT_KEY_ALIAS) requireSecureHardware(generated, alias, justGenerated = true) else generated
    }

    private fun generateKey(alias: String): SecretKey {

        // Generar nueva clave AES-256 anclada a hardware
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
            if (alias == VAULT_KEY_ALIAS) {
                // El chip solo usa la clave si el usuario se identificó (huella o PIN) hace menos de 30 s
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
                // Ojo: con una validez de 30 s Android ignora esta opción (solo vale para claves sin validez, de un
                // solo uso con huella). Lo que sí anula la clave es quitar o restablecer el bloqueo de pantalla
                builder.setInvalidatedByBiometricEnrollment(true)
                // Inutilizable mientras la pantalla está bloqueada
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setUnlockedDeviceRequired(true)
                }
            }
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }
            return builder.build()
        }

        // Si el hardware soporta StrongBox (módulo de seguridad dedicado), se intenta usar
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
            // Sin PIN, patrón ni contraseña en el teléfono no se puede crear una clave que exija autenticación
            if (alias == VAULT_KEY_ALIAS) throw VaultUnavailableException(VaultUnavailableException.Reason.NO_SCREEN_LOCK, e)
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
     * ¿Android anuló la clave de la Bóveda? Pasa si se quita o restablece el bloqueo de pantalla. Las contraseñas
     * cifradas con ella ya no se pueden descifrar. No crea la clave si aún no existe.
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
        // Bloqueada (falta huella o PIN reciente) u otro motivo: no está anulada
        false
    }

    /** Borra la clave anulada y las contraseñas que ya no se pueden descifrar, para empezar una Bóveda nueva. */
    fun resetInvalidatedVaultKey(context: Context) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(VAULT_KEY_ALIAS)
        verifiedHardwareAliases.remove(VAULT_KEY_ALIAS)
        saveAll(context, emptyList())
    }

    /** ¿La clave de la Bóveda exige autenticación del usuario? (lo informa el propio Keystore) */
    fun vaultKeyRequiresAuth(): Boolean = try {
        val key = getOrCreateKey(VAULT_KEY_ALIAS)
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        info.isUserAuthenticationRequired
    } catch (_: Exception) {
        false
    }

    /**
     * Dónde vive realmente la clave maestra, según el propio Keystore: "STRONGBOX", "TEE", "SOFTWARE"
     * o "DESCONOCIDO" si Android no lo informa. Si la Bóveda rechaza la clave por estar en software, lo dice.
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
            false // Si no se puede comprobar, no se afirma que esté en hardware
        }
    }

    /**
     * Cifra una contraseña en memoria RAM viva utilizando AES-256-GCM.
     * Inmediatamente después de cifrar, el buffer de entrada se destruye (Zeroization).
     */
    @Synchronized
    fun encryptPassword(passwordChars: CharArray, alias: String = VAULT_KEY_ALIAS): Pair<String, String> {
        // El Keystore de hardware genera obligatoriamente el IV aleatorio en modo ENCRYPT_MODE
        val cipher = cipherFor(Cipher.ENCRYPT_MODE, alias)
        val iv = cipher.iv

        // Convertir temporalmente a bytes para el Cipher y destruirlo de inmediato
        val byteBuffer = java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(passwordChars))
        val passwordBytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(passwordBytes)

        val encryptedBytes = cipher.doFinal(passwordBytes)

        // ZEROIZATION ACTIVA: sobreescribir bytes en memoria RAM
        Arrays.fill(passwordBytes, 0.toByte())

        val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)

        return Pair(encryptedBase64, ivBase64)
    }

    /**
     * Descifra una credencial directamente a un array de caracteres mutable (`CharArray`).
     * Nunca devuelve un `String` inmutable para prevenir fugas en el Garbage Collector.
     */
    @Synchronized
    fun decryptPassword(encryptedBase64: String, ivBase64: String, alias: String = VAULT_KEY_ALIAS): CharArray {
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)

        val decryptedBytes = try {
            cipherFor(Cipher.DECRYPT_MODE, alias, iv).doFinal(encryptedBytes)
        } catch (e: javax.crypto.AEADBadTagException) {
            // Contraseñas guardadas antes de la clave v2 (cifradas con la clave original): esa clave también
            // tiene que vivir en hardware seguro para que la Bóveda la use
            if (alias != VAULT_KEY_ALIAS) throw e
            val legacyKey = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .let { (it.getEntry(APP_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey } ?: throw e
            requireSecureHardware(legacyKey, APP_KEY_ALIAS, justGenerated = false)
            cipherFor(Cipher.DECRYPT_MODE, APP_KEY_ALIAS, iv).doFinal(encryptedBytes)
        }

        // Decodificar a CharArray
        val charBuffer = java.nio.charset.StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(decryptedBytes))
        val resultChars = CharArray(charBuffer.remaining())
        charBuffer.get(resultChars)

        // ZEROIZATION del buffer intermedio de bytes
        Arrays.fill(decryptedBytes, 0.toByte())

        return resultChars
    }

    /**
     * Higiene de Memoria: Limpieza activa de caracteres confidenciales.
     */
    fun wipe(chars: CharArray) {
        Arrays.fill(chars, '\u0000')
    }

    /**
     * Higiene de Memoria: Limpieza activa de bytes confidenciales.
     */
    fun wipe(bytes: ByteArray) {
        Arrays.fill(bytes, 0.toByte())
    }

    // =========================================================================
    // ANTI-PHISHING Y CANONICALIZACIÓN DE DOMINIOS (eTLD+1)
    // =========================================================================

    /**
     * Extrae el dominio raíz canónico estricto de una URL para evitar ataques de subdominio.
     * Ejemplo: "https://login.banco.com.es:8443/auth" -> "banco.com.es"
     * Ejemplo falso: "https://paypal.com.evil-phish.net" -> "evil-phish.net" (detecta discrepancia)
     */
    fun extractCanonicalDomain(rawUrl: String, context: Context? = null): String {
        if (rawUrl.isBlank()) return ""
        return try {
            var clean = rawUrl.trim().lowercase()
            if (clean.startsWith("http://")) clean = clean.substring(7)
            if (clean.startsWith("https://")) clean = clean.substring(8)

            // Quitar credenciales user:pass@
            val atIdx = clean.indexOf('@')
            if (atIdx != -1) clean = clean.substring(atIdx + 1)

            // Quitar path y query
            val slashIdx = clean.indexOf('/')
            if (slashIdx != -1) clean = clean.substring(0, slashIdx)

            // Quitar puerto :8080
            val colonIdx = clean.indexOf(':')
            if (colonIdx != -1) clean = clean.substring(0, colonIdx)

            val effectiveCtx = context ?: SendaGeckoEngine.appContext
            PublicSuffixList.getRegistrableDomain(clean, effectiveCtx)
        } catch (e: Exception) {
            rawUrl
        }
    }

    /**
     * Valida si una URL actual coincide exactamente con el dominio de la credencial registrada.
     */
    fun matchesDomain(currentUrl: String, credentialDomain: String): Boolean {
        val currentDomain = extractCanonicalDomain(currentUrl)
        val targetDomain = extractCanonicalDomain(credentialDomain)
        return currentDomain.isNotBlank() && targetDomain.isNotBlank() && currentDomain.equals(targetDomain, ignoreCase = true)
    }

    // =========================================================================
    // PERSISTENCIA Y ALMACENAMIENTO DE LA BÓVEDA
    // =========================================================================

    /**
     * Carga todas las credenciales guardadas en la bóveda local.
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
     * Guarda una credencial en la bóveda (añade o actualiza si ya existe para ese dominio y usuario).
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

    /**
     * Modifica una credencial existente (sitio, usuario y contraseña). Devuelve false si ya hay otra cuenta
     * con el mismo sitio y usuario, para no dejar dos entradas iguales.
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
     * Elimina una credencial por su identificador único.
     */
    fun deleteCredential(context: Context, credentialId: String) {
        val currentList = getCredentials(context).filter { it.id != credentialId }
        saveAll(context, currentList)
    }

    private fun saveAll(context: Context, list: List<VaultCredential>) {
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error persistiendo bóveda: ${e.message}")
        }
    }

    // =========================================================================
    // HIGIENE DE PORTAPAPELES (ANTI-SNOOPING & AUTO-DESTRUCCIÓN A 30s)
    // =========================================================================

    /**
     * Copia una contraseña al portapapeles con bandera de contenido sensible (Android 13+)
     * e inicia un temporizador de autodestrucción a los 30 segundos.
     */
    fun copyToClipboardSecurely(context: Context, label: String, passwordChars: CharArray) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val passString = String(passwordChars)
        val clip = ClipData.newPlainText(label, passString)

        // Notificar a Android 13+ que el contenido es confidencial (oculta previsualización visual)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }

        clipboard.setPrimaryClip(clip)

        // Programar destrucción en 30 segundos
        scope.launch {
            delay(30_000L)
            try {
                // Desde Android 10 una app en segundo plano no puede leer el portapapeles (devuelve null).
                // En ese caso se borra igualmente: es preferible perder una copia posterior que dejar la contraseña
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
    // GENERADOR DE CONTRASEÑAS DE ALTA ENTROPÍA
    // =========================================================================

    /**
     * Genera una contraseña criptográficamente fuerte con entropía verificable.
     */
    fun generateStrongPassword(
        length: Int = 18,
        includeUpper: Boolean = true,
        includeLower: Boolean = true,
        includeDigits: Boolean = true,
        includeSymbols: Boolean = true
    ): Pair<CharArray, Double> {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ" // Sin I, O confusos
        val lower = "abcdefghijkmnopqrstuvwxyz" // Sin l confuso
        val digits = "23456789"                  // Sin 0, 1 confusos
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

        // Al azar puro, ~13 % de las claves de 20 salían sin dígito: se garantiza uno de cada clase elegida
        // y luego se mezcla todo para que esas posiciones no sean predecibles
        val result = CharArray(length)
        for (i in 0 until length) {
            val source = if (i < classes.size) classes[i] else pool
            result[i] = source[secureRandom.nextInt(source.length)]
        }
        for (i in length - 1 downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val tmp = result[i]; result[i] = result[j]; result[j] = tmp
        }

        // Entropía de Shannon: E = L * log2(poolSize)
        val entropyBits = length * (Math.log(pool.length.toDouble()) / Math.log(2.0))

        return Pair(result, entropyBits)
    }
}

/** La clave de la Bóveda necesita que el usuario se identifique (huella o PIN) antes de usarse. */
class VaultLockedException(cause: Throwable? = null) : Exception("Bóveda bloqueada: identifícate con huella o PIN", cause)

/** La Bóveda no puede usarse: no hay bloqueo de pantalla o la clave quedó invalidada. */
class VaultUnavailableException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    /** NO_SECURE_HARDWARE: el teléfono no puede guardar la clave en StrongBox ni en un TEE. */
    enum class Reason { NO_SCREEN_LOCK, KEY_INVALIDATED, NO_SECURE_HARDWARE }
}
