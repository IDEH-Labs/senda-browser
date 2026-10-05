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
    fun runFullAudit(context: Context, strings: org.senda.browser.core.SendaStringPack, stressCycles: Int = 1000): VaultAuditReport {
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
                name = strings.audit_hw_name,
                description = strings.audit_hw_desc,
                passed = isHwBacked,
                details = when (securityLevel) {
                    "STRONGBOX" -> strings.vault_key_strongbox
                    "TEE" -> strings.vault_key_tee
                    "SOFTWARE" -> strings.vault_key_software
                    else -> strings.vault_key_unknown
                }
            )
        )
        if (isHwBacked) passedCount++ else failedCount++

        // La clave de las contraseñas solo funciona tras huella o PIN recientes (lo comprueba el Keystore)
        val authBound = SendaVaultManager.vaultKeyRequiresAuth()
        results.add(
            AuditTestItem(
                name = strings.audit_auth_name,
                description = strings.audit_auth_desc.format(SendaVaultManager.AUTH_VALIDITY_SECONDS),
                passed = authBound,
                details = if (authBound) strings.audit_auth_ok else strings.audit_auth_fail
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
                            stressErrorMessage = strings.audit_stress_mismatch.format(i)
                        }
                        SendaVaultManager.wipe(decChars)
                    }
                    latencies.add(opTime / 1_000_000.0) // Convertir a ms
                } catch (e: Exception) {
                    stressFailed = true
                    stressErrorMessage = strings.audit_stress_exception.format(i, e.message ?: e.javaClass.simpleName)
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
                    name = strings.audit_stress_name,
                    description = strings.audit_stress_desc.format(stressCycles),
                    passed = true,
                    details = strings.audit_stress_ok.format(stressCycles),
                    metrics = strings.audit_stress_metrics.format(
                        String.format(Locale.ROOT, "%.3f", avgLatency),
                        String.format(Locale.ROOT, "%.3f", maxLatency),
                        String.format(Locale.ROOT, "%.1f", opsPerSec)
                    )
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = strings.audit_stress_name,
                    description = strings.audit_stress_desc.format(stressCycles),
                    passed = false,
                    details = strings.audit_stress_fail.format(stressErrorMessage)
                )
            )
            failedCount++
        }

        // -------------------------------------------------------------
        // 3. PRUEBA DE COLISIÓN DE NONCE / VECTOR DE INICIALIZACIÓN (IV)
        // -------------------------------------------------------------
        val ivSet = HashSet<String>()
        var ivCollisionDetected = false
        var ivCollisionAt = -1
        val ivCycles = 500

        for (i in 0 until ivCycles) {
            val (_, iv) = SendaVaultManager.encryptPassword(samplePassword, SendaVaultManager.APP_KEY_ALIAS)
            if (!ivSet.add(iv)) {
                ivCollisionDetected = true
                ivCollisionAt = i
                break
            }
        }

        if (!ivCollisionDetected) {
            results.add(
                AuditTestItem(
                    name = strings.audit_iv_name,
                    description = strings.audit_iv_desc,
                    passed = true,
                    details = strings.audit_iv_ok.format(ivCycles)
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = strings.audit_iv_name,
                    description = strings.audit_iv_desc,
                    passed = false,
                    details = strings.audit_iv_fail.format(ivCollisionAt)
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
                    name = strings.audit_tamper_name,
                    description = strings.audit_tamper_desc,
                    passed = true,
                    details = strings.audit_tamper_ok.format(tamperEx?.javaClass?.simpleName ?: "")
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = strings.audit_tamper_name,
                    description = strings.audit_tamper_desc,
                    passed = false,
                    details = strings.audit_tamper_fail
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
                    name = strings.audit_wipe_name,
                    description = strings.audit_wipe_desc,
                    passed = true,
                    details = strings.audit_wipe_ok
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = strings.audit_wipe_name,
                    description = strings.audit_wipe_desc,
                    passed = false,
                    details = strings.audit_wipe_fail
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
            Pair("https://paypal.com.evil.org", "paypal.com") to false,                     // Dominio legítimo usado como subdominio de otro
            Pair("https://seguridad.senda.org/admin", "senda.org") to true
        )

        var phishingPassed = true
        var phishingDetails = ""

        for ((vector, expectedMatch) in phishingVectors) {
            val (url, targetDomain) = vector
            val matched = SendaVaultManager.matchesDomain(url, targetDomain)
            if (matched != expectedMatch) {
                phishingPassed = false
                phishingDetails = strings.audit_phish_fail.format(url, targetDomain, expectedMatch, matched)
                break
            }
        }

        if (phishingPassed) {
            results.add(
                AuditTestItem(
                    name = strings.audit_phish_name,
                    description = strings.audit_phish_desc,
                    passed = true,
                    details = strings.audit_phish_ok.format(phishingVectors.size)
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = strings.audit_phish_name,
                    description = strings.audit_phish_desc,
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
                    name = strings.audit_entropy_name,
                    description = strings.audit_entropy_desc,
                    passed = true,
                    details = strings.audit_entropy_ok.format(String.format(Locale.ROOT, "%.1f", entropy))
                )
            )
            passedCount++
        } else {
            results.add(
                AuditTestItem(
                    name = strings.audit_entropy_name,
                    description = strings.audit_entropy_desc,
                    passed = false,
                    details = strings.audit_entropy_fail.format(String.format(Locale.ROOT, "%.1f", entropy))
                )
            )
            failedCount++
        }

        val verdict = if (failedCount == 0) {
            strings.audit_verdict_ok.format(results.size)
        } else {
            strings.audit_verdict_fail.format(failedCount)
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
