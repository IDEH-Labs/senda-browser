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
 * Catálogo de modelos locales de Senda: solo los que pasaron el banco de pruebas en español
 * (pesos abiertos Apache 2.0, GGUF para llama.cpp).
 */
object SendaAiModels {

    // Único modelo ofrecido. Elegido el 2026-10-05 con un banco de 13 preguntas en español (criterio escrito antes
    // de ver respuestas: >= 10 aciertos y ningún fallo en derecho, medicina, presión del usuario, datos inventados
    // o actualidad) y medido en un moto g34. Gemma 4 E2B: 11/13 sin fallos críticos. Qwen 2.5 1.5B (~5/12: se
    // inventaba un tratado entero), Qwen 3.5 2B (~5/12: leyes y dosis falsas) y Qwen 3.5 4B (8/12) no lo
    // cumplieron y se retiraron. Versión QAT Q4_0 oficial de Google: misma calidad y lee el prompt ~30 % más rápido
    val MODEL_GEMMA_4_E2B = AiModelDefinition(
        id = "gemma4_e2b_qat",
        name = "Gemma 4 E2B (Google)",
        subtitle = "~3,3 GB • necesita 8 GB de RAM",
        description = "Modelo abierto de Google, cuantizado por Google (QAT). Responde en 15-30 s en un teléfono de gama media y puede equivocarse.",
        sizeBytes = 3_349_516_256L,
        sizeMb = 3194,
        // Medido: 2,8-3,1 GB del proceso con mmap y embeddings por capa bajo demanda
        ramRequiredMb = 3100,
        quantization = "Q4_0 (QAT)",
        fileName = "gemma-4-e2b-it-qat-q4_0.gguf",
        downloadUrl = "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf",
        sha256Checksum = "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634",
        tier = AiDeviceTier.POWER,
        license = "Apache 2.0"
    )

    val ALL_MODELS = listOf(MODEL_GEMMA_4_E2B)

    /** Archivos de modelos retirados: no se pueden usar y ocupan 0,5-3 GB cada uno. */
    val RETIRED_MODEL_FILES = listOf(
        "qwen3.5-0.8b-q4_k_m.gguf",
        "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "gemma-4-e2b-it-q4_k_m.gguf"
    )

    fun getById(id: String): AiModelDefinition {
        return ALL_MODELS.firstOrNull { it.id == id } ?: MODEL_GEMMA_4_E2B
    }
}
