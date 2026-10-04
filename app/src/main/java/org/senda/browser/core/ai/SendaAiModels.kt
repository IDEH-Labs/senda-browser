package org.senda.browser.core.ai

/**
 * Definición técnica de un modelo de lenguaje local soberano soportado por Senda.
 */
data class AiModelDefinition(
    val id: String,
    val name: String,
    val subtitle: String,
    val description: String,
    val sizeBytes: Long,
    val sizeMb: Int,
    val ramRequiredMb: Int,
    val quantization: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256Checksum: String,
    val tier: AiDeviceTier,
    val license: String = "Apache 2.0"
)

/**
 * Catálogo Soberano de Modelos Locales para Senda Browser.
 * Todos los modelos utilizan pesos abiertos bajo licencia Apache 2.0,
 * compatibles con inferencia offline en formato GGUF (llama.cpp / GGML).
 */
object SendaAiModels {

    // 1. SEGMENTO BASE / EFICIENTE (Dispositivos de 4 GB o memoria ajustada)
    // Qwen 3.5 Small (0.8B) - Nueva Generación Edge (Marzo 2026, Apache 2.0)
    val MODEL_QWEN_3_5_0_8B = AiModelDefinition(
        id = "qwen3.5_0.8b",
        name = "Qwen 3.5 Small (0.8B)",
        subtitle = "Base / Ultra Eficiente • ~510 MB • Seguro en 4 GB",
        description = "Elección base de Senda. Cumple el presupuesto estricto de <= 1.0 GB de memoria total (pesos + KV cache 2048 tokens). Rápido, seguro y sin riesgo de OOM.",
        sizeBytes = 532_517_120L,
        sizeMb = 508,
        ramRequiredMb = 850,
        quantization = "Q4_K_M",
        fileName = "qwen3.5-0.8b-q4_k_m.gguf",
        // Repositorio real de unsloth (el anterior, «-Instruct-GGUF», no existe: daba error 401)
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
        sha256Checksum = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
        tier = AiDeviceTier.LIGHT,
        license = "Apache 2.0"
    )

    // 2. SEGMENTO EQUILIBRADO / PRODUCTIVIDAD (Dispositivos de 6 GB a 8 GB)
    // Qwen 2.5 (1.5B) - Estándar de Redacción y Código (Apache 2.0)
    val MODEL_QWEN_1_5B = AiModelDefinition(
        id = "qwen2.5_1.5b",
        name = "Qwen 2.5 (1.5B)",
        subtitle = "Equilibrado • ~1,1 GB • Alta Calidad de Redacción",
        description = "Estándar de calidad en redacción formal y código en Senda Codex. Huella moderada (~1.3 GB) para teléfonos con 6 GB a 8 GB de RAM.",
        sizeBytes = 1_117_320_736L,
        sizeMb = 1066,
        ramRequiredMb = 1300,
        quantization = "Q4_K_M",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sha256Checksum = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        tier = AiDeviceTier.BALANCED,
        license = "Apache 2.0"
    )

    // 3. SEGMENTO POTENCIA / BORDE AVANZADO (Dispositivos de 8 GB a 12 GB)
    // Gemma 4 (E2B) - Google Edge (Abril 2026, Apache 2.0)
    val MODEL_GEMMA_4_E2B = AiModelDefinition(
        id = "gemma4_e2b",
        name = "Gemma 4 (E2B)",
        subtitle = "Potencia / Google Edge • ~3,1 GB • Razonamiento Avanzado",
        description = "Modelo de borde de última generación de Google bajo licencia libre Apache 2.0. Base de Gemini Nano 4 para razonamiento complejo y análisis profundo.",
        sizeBytes = 3_106_738_272L,
        sizeMb = 2963,
        // Los pesos ya ocupan ~3 GB: con 2,1 GB declarados un móvil justo se quedaba sin memoria
        ramRequiredMb = 3600,
        quantization = "Q4_K_M",
        fileName = "gemma-4-e2b-it-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
        sha256Checksum = "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8",
        tier = AiDeviceTier.POWER,
        license = "Apache 2.0"
    )

    val ALL_MODELS = listOf(
        MODEL_QWEN_3_5_0_8B,
        MODEL_QWEN_1_5B,
        MODEL_GEMMA_4_E2B
    )

    fun getById(id: String): AiModelDefinition {
        return ALL_MODELS.firstOrNull { it.id == id } ?: MODEL_QWEN_3_5_0_8B
    }
}
