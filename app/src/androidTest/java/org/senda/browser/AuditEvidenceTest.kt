package org.senda.browser

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.security.SendaVaultAuditRunner
import org.senda.browser.core.security.SendaVaultManager
import org.senda.browser.core.SendaStrings

/**
 * Pruebas de evidencia de la auditoría del 2026-10-04. No afirman nada: registran en logcat (etiqueta AUDIT)
 * lo que el código hace de verdad, para que cada hallazgo se apoye en una salida observable.
 */
@RunWith(AndroidJUnit4::class)
class AuditEvidenceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(name: String, value: String) {
        value.lines().forEach { Log.i("AUDIT", "[$name] $it") }
    }

    @Test
    fun a6_vaultGeneratorAndAudit() {
        val n = 20000
        var noDigit = 0; var failsAudit = 0
        repeat(n) {
            val (p, entropy) = SendaVaultManager.generateStrongPassword(length = 20)
            val hasDigit = p.any { it.isDigit() }
            val ok = entropy >= 100.0 && p.any { it.isUpperCase() } && p.any { it.isLowerCase() } && hasDigit &&
                p.any { "!@#$%^&*()-_=+[]{}|;:,.<>?".contains(it) }
            if (!hasDigit) noDigit++
            if (!ok) failsAudit++
        }
        log("a6", "contraseñas de 20 sin dígito: $noDigit/$n = ${"%.2f".format(100.0 * noDigit / n)} %")
        log("a6", "contraseñas que harían fallar la prueba de entropía: $failsAudit/$n = ${"%.2f".format(100.0 * failsAudit / n)} %")
        val strings = SendaStrings.get("ES", context)
        var auditFails = 0
        val runs = 15
        repeat(runs) {
            val rep = SendaVaultAuditRunner.runFullAudit(context, strings, stressCycles = 5)
            if (rep.results.any { !it.passed && it.name == strings.audit_entropy_name }) auditFails++
            if (it == 0) rep.results.forEach { item -> log("a6", "auditoría: ${item.name} -> ${if (item.passed) "OK" else "FALLA"} (${item.details})") }
        }
        log("a6", "ejecuciones reales de la auditoría con fallo de entropía: $auditFails/$runs")
    }

    @Test
    fun a7_historyClearRace() {
        DestructiveTestGuard.requireExplicitPermission("borra el historial")
        val prefs = PreferencesManager(context)
        prefs.clearHistory()
        Thread.sleep(500)
        repeat(30) { prefs.addHistoryItem("Página $it", "https://ejemplo.org/$it") }
        prefs.clearHistory()
        Thread.sleep(3000)
        log("a7", "entradas que quedaron tras «borrar historial»: ${prefs.getHistory().size}")
        prefs.clearHistory()
    }

}
