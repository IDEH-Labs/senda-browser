package org.senda.browser

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.security.SendaVaultAuditRunner

@RunWith(AndroidJUnit4::class)
class VaultAuditInstrumentedTest {

    @Test
    fun executeForensicAuditAndStressTest() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val report = SendaVaultAuditRunner.runFullAudit(context, org.senda.browser.core.SendaStrings.get("ES", context), stressCycles = 1000)

        println("=================================================================")
        println("         SENDA SOBERANA - REPORTE DE AUDITORÍA FORENSE           ")
        println("=================================================================")
        println("Veredicto Final: ${report.finalVerdict}")
        println("Pruebas Ejecutadas: ${report.totalTests}")
        println("Pruebas Aprobadas: ${report.passedTests}")
        println("Pruebas Fallidas: ${report.failedTests}")
        println("Ciclos de Estrés AEAD: ${report.totalStressCycles}")
        println("Latencia Promedio: ${String.format("%.4f", report.avgLatencyMs)} ms")
        println("Latencia Pico (Max): ${String.format("%.4f", report.maxLatencyMs)} ms")
        println("Rendimiento Criptográfico: ${String.format("%.1f", report.throughputOpsPerSec)} operaciones/seg")
        println("Anclaje en Silicio Seguro: ${report.isHardwareBacked}")
        println("-----------------------------------------------------------------")
        for (item in report.results) {
            println("[${if (item.passed) "PASS" else "FAIL"}] ${item.name}")
            println("   Detalle: ${item.details}")
            if (item.metrics != null) println("   Métricas: ${item.metrics}")
        }
        println("=================================================================")

        assertEquals(0, report.failedTests)
        assertTrue(report.passedTests >= 6)
        assertTrue(report.avgLatencyMs < 150.0) // Límite realista para operaciones en silicio TEE Keystore
    }
}
