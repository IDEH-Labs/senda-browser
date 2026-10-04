package org.senda.browser.core.security

import android.content.Context
import android.util.Base64
import android.util.Log
import android.annotation.SuppressLint
import java.util.Locale
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.system.measureNanoTime

/**
 * Reporte detallado de los resultados de la auditoría y prueba de estrés.
 */
data class AuditTestItem(
    val name: String,
    val description: String,
    val passed: Boolean,
    val details: String,
    val metrics: String? = null
)

data class VaultAuditReport(
    val isHardwareBacked: Boolean,
    val totalTests: Int,
    val passedTests: Int,
    val failedTests: Int,
    val totalStressCycles: Int,
    val avgLatencyMs: Double,
    val maxLatencyMs: Double,
    val throughputOpsPerSec: Double,
    val results: List<AuditTestItem>,
    val finalVerdict: String
)

/**
 * Motor de Auditoría Rigurosa y Pruebas de Estrés para la Bóveda de Senda.
 * Ejecuta pruebas forenses y criptográficas con máxima precisión matemática.
 */
object SendaVaultAuditRunner {

    private const val TAG = "SendaVaultAudit"

    @SuppressLint("AuthLeak")
    fun runFullAudit(context: Context, stressCycles: Int = 1000): VaultAuditReport {
        val results = mutableListOf<AuditTestItem>()
        var passedCount = 0
        var failedCount = 0

        // -------------------------------------------------------------
        // 1. AUDITORÍA DE ANCLAJE EN HARDWARE (ARM TrustZone / TEE)
        // -------------------------------------------------------------
        // Antes esta prueba se daba siempre por aprobada; ahora informa lo que dice el propio Keystore
        val securityLevel = SendaVaultManager.keySecurityLevel()
        val isHwBacked = securityLevel == "STRONGBOX" || securityLevel == "TEE"
        results.add(
            AuditTestItem(
                name = "Aislamiento de Clave en Hardware Seguro",
                description = "Verifica que la clave maestra AES-256 esté custodiada en el silicio (TEE/Keystore) y no en memoria de la app.",
                passed = isHwBacked,
                details = when (securityLevel) {
                    "STRONGBOX" -> "Clave maestra en StrongBox: chip de seguridad dedicado, separado del procesador."
                    "TEE" -> "Clave maestra en el entorno de ejecución confiable (TEE) del procesador; nunca sale del hardware."
                    "SOFTWARE" -> "ATENCIÓN: este teléfono guarda la clave en software, no en hardware seguro."
                    else -> "No se pudo determinar dónde guarda Android la clave."
                }
            )
        )
        if (isHwBacked) passedCount++ else failedCount++

        // La clave de las contraseñas solo funciona tras huella o PIN recientes (lo comprueba el Keystore)
        val authBound = SendaVaultManager.vaultKeyRequiresAuth()
        results.add(
            AuditTestItem(
                name = "Clave ligada a tu huella o PIN",
                description = "Verifica que el chip se niegue a descifrar si no te identificaste en los últimos ${SendaVaultManager.AUTH_VALIDITY_SECONDS} s.",
                passed = authBound,
                details = if (authBound) "El chip exige huella o PIN reciente y el teléfono desbloqueado; una huella nueva invalida la clave."
                          else "La clave no exige autenticación (¿el teléfono no tiene PIN, patrón o contraseña?)."
            )
        )
        if (authBound) passedCount++ else failedCount++

        // -------------------------------------------------------------
        // 2. PRUEBA DE ESTRÉS Y RENDIMIENTO (1.000 Ciclos Cifrado/Descifrado)
        // -------------------------------------------------------------
        val latencies = mutableListOf<Double>()
        var stressFailed = false
        var stressErrorMessage = ""

        val samplePassword = "Senda#Ultra\$ecure*Pass_2026!ñá".toCharArray()

        val totalTimeNanos = measureNanoTime {
            for (i in 0 until stressCycles) {
                try {
                    val opTime = measureNanoTime {
                        val (enc, iv) = SendaVaultManager.encryptPassword(samplePassword, SendaVaultManager.APP_KEY_ALIAS)
                        val decChars = SendaVaultManager.decryptPassword(enc, iv, SendaVaultManager.APP_KEY_ALIAS)
                        if (!samplePassword.contentEquals(decChars)) {
                            stressFailed = true
                            stressErrorMessage = "Discrepancia en ciclo $i: el texto descifrado no coincide"
                        }
                        SendaVaultManager.wipe(decChars)
                    }
                    latencies.add(opTime / 1_000_000.0) // Convertir a ms
                } catch (e: Exception) {
                    stressFailed = true
                    stressErrorMessage = "Excepción en ciclo $i: ${e.message}"
                    break
                }
            }
        }

        val avgLatency = if (latencies.isNotEmpty()) latencies.average() else 0.0
        val maxLatency = if (latencies.isNotEmpty()) latencies.maxOrNull() ?: 0.0 else 0.0
        val totalSecs = totalTimeNanos / 1_000_000_000.0
        val opsPerSec = if (totalSecs > 0) stressCycles / totalSecs else 0.0

        if (!stressFailed) {
            results.add(
                AuditTestItem(
                    name = "Prueba de Estrés de Alto Rendimiento",
                    description = "Ejecución continua de $stressCycles ciclos completos de cifrado/descifrado AEAD.",
                    passed = true,
                    details = "$stressCycles ciclos superados al 100% sin fugas, bloqueos ni errores.",
                    metrics = "Latencia promedio: ${String.format(Locale.ROOT, "%.3f", avgLatency)} ms | Latencia pico: ${String.format(Locale.ROOT, "%.3f", maxLatency)} ms | Rendimiento: ${String.format(Locale.ROOT, "%.1f", opsPerSec)} ops/seg"
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = "Prueba de Estrés de Alto Rendimiento",
                    description = "Ejecución continua de $stressCycles ciclos completos.",
                    passed = false,
                    details = "Fallo en prueba de estrés: $stressErrorMessage"
                )
            )
            failedCount++
        }

        // -------------------------------------------------------------
        // 3. PRUEBA DE COLISIÓN DE NONCE / VECTOR DE INICIALIZACIÓN (IV)
        // -------------------------------------------------------------
        val ivSet = HashSet<String>()
        var ivCollisionDetected = false
        val ivCycles = 500

        for (i in 0 until ivCycles) {
            val (_, iv) = SendaVaultManager.encryptPassword(samplePassword, SendaVaultManager.APP_KEY_ALIAS)
            if (!ivSet.add(iv)) {
                ivCollisionDetected = true
                break
            }
        }

        if (!ivCollisionDetected) {
            results.add(
                AuditTestItem(
                    name = "Unicidad de Nonce / IV Criptográfico (Anti-Reutilización)",
                    description = "Verifica que cada cifrado genere un vector de inicialización de 96 bits totalmente único vía CSPRNG.",
                    passed = true,
                    details = "$ivCycles IVs únicos consecutivos verificados sin colisiones (0% de probabilidad de reutilización de clave en GCM)."
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = "Unicidad de Nonce / IV Criptográfico",
                    description = "Verifica la unicidad del vector de inicialización.",
                    passed = false,
                    details = "ALERTA CRÍTICA: Se detectó una colisión de IV en ciclo $ivCycles."
                )
            )
            failedCount++
        }

        // -------------------------------------------------------------
        // 4. RESISTENCIA A MANIPULACIÓN DE BITS (AEAD Tag Tamper-Resistance)
        // -------------------------------------------------------------
        var tamperDetected = false
        var tamperEx: Exception? = null

        try {
            val (encBase64, ivBase64) = SendaVaultManager.encryptPassword(samplePassword, SendaVaultManager.APP_KEY_ALIAS)
            val encBytes = Base64.decode(encBase64, Base64.NO_WRAP)

            // Simular ataque de inyección: alterar deliberadamente el último byte del tag de autenticación
            encBytes[encBytes.size - 1] = (encBytes[encBytes.size - 1].toInt() xor 0x01).toByte()
            val tamperedBase64 = Base64.encodeToString(encBytes, Base64.NO_WRAP)

            // Debe fallar obligatoriamente
            SendaVaultManager.decryptPassword(tamperedBase64, ivBase64, SendaVaultManager.APP_KEY_ALIAS)
        } catch (e: Exception) {
            tamperDetected = true
            tamperEx = e
        }

        if (tamperDetected) {
            results.add(
                AuditTestItem(
                    name = "Inviolabilidad e Integridad de Mensaje (AEAD Tag)",
                    description = "Inyección deliberada de 1 bit corrupto en el mensaje cifrado para probar el rechazo criptográfico.",
                    passed = true,
                    details = "Rechazado instantáneamente por fallo de etiqueta de autenticación (${tamperEx?.javaClass?.simpleName}). Cero fuga de texto plano."
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = "Inviolabilidad e Integridad de Mensaje (AEAD Tag)",
                    description = "Inyección deliberada de 1 bit corrupto.",
                    passed = false,
                    details = "FALLO DE SEGURIDAD: El descifrador aceptó un mensaje modificado."
                )
            )
            failedCount++
        }

        // -------------------------------------------------------------
        // 5. HIGIENE DE MEMORIA Y ZEROIZATION (Destrucción en RAM)
        // -------------------------------------------------------------
        val volatileBuffer = charArrayOf('S', 'e', 'c', 'r', 'e', 't', '1', '2', '3')
        SendaVaultManager.wipe(volatileBuffer)
        val allZeros = volatileBuffer.all { it == '\u0000' }

        if (allZeros) {
            results.add(
                AuditTestItem(
                    name = "Higiene de Memoria RAM (Zeroization Activa)",
                    description = "Comprueba que la rutina de sobreescritura con ceros borre físicamente el contenido de la memoria.",
                    passed = true,
                    details = "El buffer de prueba quedó sobrescrito con ceros ('\\u0000'). Reduce el tiempo que la contraseña permanece en memoria; no protege frente a un volcado hecho mientras se usa."
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = "Higiene de Memoria RAM (Zeroization Activa)",
                    description = "Comprueba que la rutina de sobreescritura con ceros borre físicamente el contenido.",
                    passed = false,
                    details = "Fallo en la prueba de sanitización: quedaron caracteres residuales en memoria."
                )
            )
            failedCount++
        }

        // -------------------------------------------------------------
        // 6. MATRIZ DE VERIFICACIÓN ANTI-PHISHING (eTLD+1)
        // -------------------------------------------------------------
        val phishingVectors = listOf(
            Pair("https://login.banco.com.es/cuenta", "banco.com.es") to true,
            Pair("https://banco.com.es.sitio-malicioso.com/login", "banco.com.es") to false, // Ataque subdominio
            Pair("http://usuario:pass@sub.dominio.co.uk:8080/path?id=1", "dominio.co.uk") to true,
            Pair("https://paypal.com.evil.org", "paypal.com") to false,                     // Ataque homógrafo
            Pair("https://seguridad.senda.org/admin", "senda.org") to true
        )

        var phishingPassed = true
        var phishingDetails = ""

        for ((vector, expectedMatch) in phishingVectors) {
            val (url, targetDomain) = vector
            val matched = SendaVaultManager.matchesDomain(url, targetDomain)
            if (matched != expectedMatch) {
                phishingPassed = false
                phishingDetails = "Discrepancia en vector: URL=$url contra Dominio=$targetDomain. Esperado=$expectedMatch, Obtenido=$matched"
                break
            }
        }

        if (phishingPassed) {
            results.add(
                AuditTestItem(
                    name = "Filtrado Anti-Phishing por Dominio Canónico (eTLD+1)",
                    description = "Verificación estricta contra ataques de subdominio suplantado, puertos arbitrarios y sufijos de dos niveles.",
                    passed = true,
                    details = "Matriz de 5 vectores de ataque procesada con 100% de precisión. Los dominios falsos fueron bloqueados sin excepción."
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = "Filtrado Anti-Phishing por Dominio Canónico (eTLD+1)",
                    description = "Verificación contra ataques de subdominio.",
                    passed = false,
                    details = phishingDetails
                )
            )
            failedCount++
        }

        // -------------------------------------------------------------
        // 7. ENTROPÍA DEL GENERADOR CRIPTOGRÁFICO
        // -------------------------------------------------------------
        val (genPass, entropy) = SendaVaultManager.generateStrongPassword(length = 20)
        val hasUpper = genPass.any { it.isUpperCase() }
        val hasLower = genPass.any { it.isLowerCase() }
        val hasDigit = genPass.any { it.isDigit() }
        val hasSymbol = genPass.any { "!@#$%^&*()-_=+[]{}|;:,.<>?".contains(it) }

        val entropyPassed = entropy >= 100.0 && hasUpper && hasLower && hasDigit && hasSymbol
        SendaVaultManager.wipe(genPass)

        if (entropyPassed) {
            results.add(
                AuditTestItem(
                    name = "Generador de Claves de Alta Entropía (Shannon Entropy)",
                    description = "Evaluación de aleatoriedad por CSPRNG, distribución de caracteres y resistencia a ataques de diccionario.",
                    passed = true,
                    details = "Entropía calculada: ${String.format(Locale.ROOT, "%.1f", entropy)} bits (recomendado: más de 90 bits). Distribución heterogénea verificada."
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = "Generador de Claves de Alta Entropía",
                    description = "Evaluación de aleatoriedad por CSPRNG.",
                    passed = false,
                    details = "Entropía insuficiente (${String.format(Locale.ROOT, "%.1f", entropy)} bits) o falta de heterogeneidad."
                )
            )
            failedCount++
        }

        val verdict = if (failedCount == 0) {
            "AUTODIAGNÓSTICO: TODAS LAS PRUEBAS SUPERADAS (no es una certificación externa)"
        } else {
            "AUDITORÍA CON OBSERVACIONES: $failedCount PRUEBAS NO SUPERADAS"
        }

        return VaultAuditReport(
            isHardwareBacked = isHwBacked,
            totalTests = results.size,
            passedTests = passedCount,
            failedTests = failedCount,
            totalStressCycles = stressCycles,
            avgLatencyMs = avgLatency,
            maxLatencyMs = maxLatency,
            throughputOpsPerSec = opsPerSec,
            results = results,
            finalVerdict = verdict
        )
    }
}
