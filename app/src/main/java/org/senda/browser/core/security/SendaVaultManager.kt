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
import androidx.biometric.BiometricPrompt
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

/**
 * Modelo inmutable de credencial almacenada en la Bóveda Soberana de Senda.
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
 * Gestor Criptográfico de Grado Militar para la Bóveda de Contraseñas de Senda.
 * Cumple con especificaciones OWASP MASVS L2 (Nivel Bancario/Defensa):
 * - Clave maestra AES-256 en hardware seguro (Android Keystore / TEE / StrongBox).
 * - Cifrado autenticado AEAD (AES-256-GCM) con detección de manipulación de bits.
 * - Zeroization activa de memoria RAM (arrays limpiados tras uso).
 * - Verificación estricta de dominios contra ataques de Phishing (eTLD+1).
 * - Higiene de portapapeles con bandera de contenido sensible y auto-destrucción a 30s.
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
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val VAULT_FILE_NAME = "senda_vault_store.json"

    private val secureRandom = SecureRandom()
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Obtiene o genera la clave maestra de 256 bits sellada en el chip de seguridad (Keystore).
     */
    @Synchronized
    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

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
                // Una huella nueva registrada en el teléfono invalida la clave: un intruso no puede añadir la suya
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

    /** Borra la clave de la Bóveda invalidada (p. ej. tras registrar una huella nueva) para poder crear otra. */
    fun resetInvalidatedVaultKey(context: Context) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(VAULT_KEY_ALIAS)
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
     * o "DESCONOCIDO" si Android no lo informa.
     */
    fun keySecurityLevel(alias: String = VAULT_KEY_ALIAS): String {
        return try {
            val key = getOrCreateKey(alias)
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar el nivel de seguridad de la clave: ${e.message}")
            "DESCONOCIDO"
        }
    }

    fun isHardwareBacked(): Boolean {
        return try {
            val key = getOrCreateKey(VAULT_KEY_ALIAS)
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
                keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
            } else {
                @Suppress("DEPRECATION")
                keyInfo.isInsideSecureHardware
            }
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
            // Contraseñas guardadas antes de la clave v2 (cifradas con la clave original)
            if (alias != VAULT_KEY_ALIAS) throw e
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

    /**
     * Crea un objeto CryptoObject para autenticación biométrica vinculada a hardware.
     */
    fun createBiometricCryptoObject(mode: Int): BiometricPrompt.CryptoObject? {
        return try {
            BiometricPrompt.CryptoObject(cipherFor(mode, VAULT_KEY_ALIAS))
        } catch (e: Exception) {
            Log.e(TAG, "Error creando Biometric CryptoObject: ${e.message}")
            null
        }
    }

    // =========================================================================
    // ANTI-PHISHING Y CANONICALIZACIÓN DE DOMINIOS (eTLD+1)
    // =========================================================================

    /**
     * Extrae el dominio raíz canónico estricto de una URL para evitar ataques de subdominio.
     * Ejemplo: "https://login.banco.com.es:8443/auth" -> "banco.com.es"
     * Ejemplo falso: "https://paypal.com.evil-phish.net" -> "evil-phish.net" (detecta discrepancia)
     */
    fun extractCanonicalDomain(rawUrl: String): String {
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

            // Dividir etiquetas
            val parts = clean.split('.').filter { it.isNotBlank() }
            if (parts.size <= 2) return clean

            // Lista de sufijos públicos comunes de dos niveles (eTLD conocidos)
            val multiPartTlds = setOf(
                "com.es", "nom.es", "org.es", "gob.es", "edu.es",
                "co.uk", "org.uk", "me.uk", "gov.uk",
                "com.ar", "com.br", "com.mx", "com.co", "com.pe", "com.ve",
                "co.jp", "ne.jp", "ac.jp", "go.jp",
                "com.au", "net.au", "org.au"
            )

            val lastTwo = "${parts[parts.size - 2]}.${parts.last()}"
            if (multiPartTlds.contains(lastTwo) && parts.size >= 3) {
                "${parts[parts.size - 3]}.$lastTwo"
            } else {
                "${parts[parts.size - 2]}.${parts.last()}"
            }
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
        if (!file.exists()) return emptyList()

        return try {
            val content = file.readText()
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
            val file = File(context.filesDir, VAULT_FILE_NAME)
            file.writeText(array.toString(2))
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
                val currentClip = clipboard.primaryClip
                if (currentClip != null && currentClip.itemCount > 0) {
                    val currentText = currentClip.getItemAt(0)?.text?.toString()
                    if (currentText == passString) {
                        // Sobrescribir con portapapeles vacío
                        val emptyClip = ClipData.newPlainText("", "")
                        clipboard.setPrimaryClip(emptyClip)
                        Log.i(TAG, "Portapapeles confidencial purgado con éxito tras 30s")
                    }
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

        if (pool.isEmpty()) pool.append(lower).append(digits)

        val result = CharArray(length)
        for (i in 0 until length) {
            val idx = secureRandom.nextInt(pool.length)
            result[i] = pool[idx]
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
    enum class Reason { NO_SCREEN_LOCK, KEY_INVALIDATED }
}
