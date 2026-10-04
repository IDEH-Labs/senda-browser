package org.senda.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.ai.AiDeviceTier
import org.senda.browser.core.ai.SendaAiModels
import org.senda.browser.core.ai.SendaHardwareProfiler
import org.senda.browser.core.ai.SendaModelManager

@RunWith(AndroidJUnit4::class)
class SendaAiHardwareProfilerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun test01_DeviceHardwareProfilerAnalysis() {
        val profile = SendaHardwareProfiler.analyze(context)

        println("==================================================")
        println(" DIAGNÓSTICO EN VIVO DE HARDWARE - SENDA BROWSER")
        println("==================================================")
        println("  Memoria RAM Total: ${String.format("%.2f", profile.totalRamGb)} GB (${profile.totalRamBytes} bytes)")
        println("  Memoria RAM Libre: ${String.format("%.2f", profile.availableRamGb)} GB")
        println("  Dispositivo Low-RAM (LMK estricto): ${profile.isLowRamDevice}")
        println("  Núcleos de CPU detectados: ${profile.cpuCores}")
        println("  Hilos de Rendimiento asignados: ${profile.performanceThreadsOptimal}")
        println("  Arquitectura ABI Primaria: ${profile.supportedAbi}")
        println("  Soporte de Vulkan activo: ${profile.hasVulkanSupport} (Versión: ${profile.vulkanVersionMajor})")
        println("  Estado térmico actual: ${profile.thermalStatusLabel}")
        println("  Perfil de IA Asignado: ${profile.recommendedTier}")
        println("  Modelo recomendado: ${profile.recommendedModelId}")
        println("  Límite de contexto seguro: ${profile.maxRecommendedContextTokens} tokens")
        println("  Resumen técnico: ${profile.diagnosticSummary}")
        println("==================================================")

        assertTrue("La memoria RAM detectada debe ser mayor a 0", profile.totalRamBytes > 0)
        assertTrue("Los núcleos de CPU deben ser al menos 1", profile.cpuCores >= 1)
        assertTrue("Los hilos óptimos deben ser entre 1 y los núcleos totales", profile.performanceThreadsOptimal in 1..profile.cpuCores)
        assertNotNull("El modelo recomendado no debe ser nulo", profile.recommendedModelId)
    }

    @Test
    fun test02_AiCatalogIntegrityAndStorage() {
        val models = SendaAiModels.ALL_MODELS
        assertEquals("Deben existir 3 niveles de modelos en el catálogo", 3, models.size)

        for (m in models) {
            assertTrue("ID de modelo debe ser válido", m.id.isNotBlank())
            assertTrue("Nombre de modelo debe ser válido", m.name.isNotBlank())
            assertTrue("El tamaño en MB debe ser positivo", m.sizeMb > 0)
            assertTrue("La URL de descarga debe ser HTTPS", m.downloadUrl.startsWith("https://"))
            assertTrue("El archivo debe tener extensión .gguf", m.fileName.endsWith(".gguf"))
            assertEquals("La licencia debe ser Apache 2.0", "Apache 2.0", m.license)
        }

        val dir = SendaModelManager.getModelsDirectory(context)
        assertTrue("El directorio de modelos debe crearse en memoria interna", dir.exists())
        println("[PASS] Catálogo de modelos de IA y directorio de almacenamiento interno verificados.")
    }
}
