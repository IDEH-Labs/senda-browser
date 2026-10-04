package org.senda.browser.core.ai

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.io.FileFilter
import java.util.Locale
import java.util.regex.Pattern

/**
 * Nivel o escalón de perfil de hardware para inferencia local soberana.
 */
enum class AiDeviceTier {
    LIGHT,      // Dispositivos de entrada (menos de 4 GB o 4 GB ajustados) -> Qwen 2.5 0.5B
    BALANCED,   // Dispositivos equilibrados (4 GB a 8 GB de RAM) -> Qwen 2.5 1.5B (Recomendado)
    POWER       // Dispositivos de alta potencia (> 8 GB de RAM y GPU avanzada) -> Qwen 2.5 3B
}

/**
 * Reporte detallado del perfil de hardware detectado en el dispositivo.
 */
data class DeviceHardwareProfile(
    val totalRamBytes: Long,
    val totalRamGb: Double,
    val availableRamBytes: Long,
    val availableRamGb: Double,
    val isLowRamDevice: Boolean,
    val cpuCores: Int,
    val performanceThreadsOptimal: Int,
    val supportedAbi: String,
    val hasVulkanSupport: Boolean,
    val vulkanVersionMajor: Int,
    val thermalStatusLabel: String,
    val isThermalThrottled: Boolean,
    val recommendedTier: AiDeviceTier,
    val recommendedModelId: String,
    val maxRecommendedContextTokens: Int,
    val diagnosticSummary: String
)

/**
 * Profiler de Hardware Ético y Adaptativo para Senda Browser.
 * Analiza las capacidades físicas reales del procesador, RAM y estado térmico
 * para garantizar máxima fluidez, estabilidad y cero congelamientos en Android.
 *
 * Principio ético: 100% de la evaluación ocurre en el dispositivo; ningún dato
 * de hardware se emite ni se comparte a la red.
 */
object SendaHardwareProfiler {

    /**
     * Diagnostica el hardware del dispositivo y genera un perfil de optimización personalizado.
     */
    fun analyze(context: Context): DeviceHardwareProfile {
        val appContext = context.applicationContext

        // 1. Memoria RAM real mediante ActivityManager
        val actManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val totalRamBytes = memInfo.totalMem
        val totalRamGb = totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        val availRamBytes = memInfo.availMem
        val availRamGb = availRamBytes / (1024.0 * 1024.0 * 1024.0)
        val isLowRam = actManager.isLowRamDevice || memInfo.lowMemory

        // 2. Núcleos de CPU y arquitectura
        val cores = getCpuCoreCount()
        val optimalThreads = calculateOptimalThreads(cores, isLowRam)
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        // 3. Aceleración gráfica Vulkan
        val pm = appContext.packageManager
        val hasVulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        val vulkanVersion = getVulkanVersionMajor(pm)

        // 4. Estado térmico
        val (thermalLabel, isThrottled) = getThermalStatus(appContext)

        // 5. Determinación inteligente del nivel de optimización (Tier)
        val (tier, modelId, maxContext) = evaluateTier(totalRamGb, availRamGb, isLowRam, cores)

        val summary = buildDiagnosticSummary(
            totalRamGb = totalRamGb,
            availRamGb = availRamGb,
            cores = cores,
            threads = optimalThreads,
            hasVulkan = hasVulkan,
            tier = tier
        )

        return DeviceHardwareProfile(
            totalRamBytes = totalRamBytes,
            totalRamGb = totalRamGb,
            availableRamBytes = availRamBytes,
            availableRamGb = availRamGb,
            isLowRamDevice = isLowRam,
            cpuCores = cores,
            performanceThreadsOptimal = optimalThreads,
            supportedAbi = primaryAbi,
            hasVulkanSupport = hasVulkan,
            vulkanVersionMajor = vulkanVersion,
            thermalStatusLabel = thermalLabel,
            isThermalThrottled = isThrottled,
            recommendedTier = tier,
            recommendedModelId = modelId,
            maxRecommendedContextTokens = maxContext,
            diagnosticSummary = summary
        )
    }

    private fun evaluateTier(
        totalRamGb: Double,
        availRamGb: Double,
        isLowRam: Boolean,
        cores: Int
    ): Triple<AiDeviceTier, String, Int> {
        return when {
            // Dispositivos de alta gama: más de 7.5 GB de RAM física, al menos 2.5 GB libres y sin advertencias
            totalRamGb >= 7.5 && availRamGb >= 2.5 && !isLowRam && cores >= 6 -> {
                Triple(AiDeviceTier.POWER, SendaAiModels.MODEL_GEMMA_4_E2B.id, 4096)
            }
            // Dispositivos equilibrados (6 GB a 8 GB de RAM con memoria libre holgada >= 1.5 GB):
            // Límite de contexto seguro en 4096 tokens para evitar que el KV cache dispare OOM
            totalRamGb >= 5.5 && availRamGb >= 1.5 && !isLowRam -> {
                Triple(AiDeviceTier.BALANCED, SendaAiModels.MODEL_QWEN_1_5B.id, 4096)
            }
            // Dispositivos de 4 GB o con RAM libre reducida (< 1.5 GB por pestañas abiertas):
            // Presupuesto estricto <= 1.0 GB total (modelo + KV cache + runtime).
            // Contexto seguro limitado a 2048 tokens.
            else -> {
                Triple(AiDeviceTier.LIGHT, SendaAiModels.MODEL_QWEN_3_5_0_8B.id, 2048)
            }
        }
    }

    /**
     * Calcula los hilos óptimos para no saturar los núcleos del sistema Android
     * ni congelar la tasa de refresco (60Hz / 120Hz) de la pantalla.
     */
    private fun calculateOptimalThreads(cores: Int, isLowRam: Boolean): Int {
        return when {
            cores <= 2 -> 1
            cores <= 4 -> 2
            cores <= 6 -> 3
            // En arquitecturas big.LITTLE estándar de 8 núcleos (2 potentes + 6 eficientes o 4+4),
            // usar exactamente 4 hilos evita saturar los núcleos de sistema operativo.
            else -> if (isLowRam) 3 else 4
        }
    }

    private fun getCpuCoreCount(): Int {
        val runtimeCores = Runtime.getRuntime().availableProcessors()
        if (runtimeCores > 0) return runtimeCores
        return try {
            val dir = File("/sys/devices/system/cpu/")
            val files = dir.listFiles(FileFilter { Pattern.matches("cpu[0-9]+", it.name) })
            files?.size ?: 4
        } catch (_: Exception) {
            4
        }
    }

    private fun getVulkanVersionMajor(pm: PackageManager): Int {
        return try {
            val features = pm.systemAvailableFeatures
            for (f in features) {
                if (PackageManager.FEATURE_VULKAN_HARDWARE_VERSION == f.name) {
                    return (f.version shr 22) and 0x3FF
                }
            }
            0
        } catch (_: Exception) {
            0
        }
    }

    private fun getThermalStatus(context: Context): Pair<String, Boolean> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null) {
                    val status = powerManager.currentThermalStatus
                    return when (status) {
                        PowerManager.THERMAL_STATUS_NONE -> "Óptimo (Frío)" to false
                        PowerManager.THERMAL_STATUS_LIGHT -> "Ligero templado" to false
                        PowerManager.THERMAL_STATUS_MODERATE -> "Moderado (Caliente)" to false
                        PowerManager.THERMAL_STATUS_SEVERE -> "Severo (Throttling activo)" to true
                        PowerManager.THERMAL_STATUS_CRITICAL -> "Crítico (Enfriando)" to true
                        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergencia" to true
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Apagado inminente" to true
                        else -> "Normal" to false
                    }
                }
            } catch (_: Exception) {}
        }
        return "Normal" to false
    }

    private fun buildDiagnosticSummary(
        totalRamGb: Double,
        availRamGb: Double,
        cores: Int,
        threads: Int,
        hasVulkan: Boolean,
        tier: AiDeviceTier
    ): String {
        val ramFormatted = String.format(Locale.US, "%.1f GB", totalRamGb)
        val availFormatted = String.format(Locale.US, "%.1f GB", availRamGb)
        val tierLabel = when (tier) {
            AiDeviceTier.LIGHT -> "Perfil Ligero (Máx. Fluidez)"
            AiDeviceTier.BALANCED -> "Perfil Equilibrado (Recomendado)"
            AiDeviceTier.POWER -> "Perfil Potencia (Máximo Razonamiento)"
        }
        val vulkanStr = if (hasVulkan) "Vulkan Activo" else "CPU ARM NEON"

        return "$cores núcleos ($threads hilos dedicados) • $ramFormatted RAM ($availFormatted libres) • $vulkanStr • $tierLabel"
    }
}
